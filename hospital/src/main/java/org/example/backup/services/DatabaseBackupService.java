package org.example.backup.services;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class DatabaseBackupService {

    private static final Logger LOG = Logger.getLogger(DatabaseBackupService.class);

    /** SQLite snapshot for mother mobile app (Dropbox + local restore on phone). */
    public static final String MOBILE_SQLITE_FILE_NAME = "kmc-latest.db";

    @ConfigProperty(name = "quarkus.datasource.username")
    String dbUser;

    @ConfigProperty(name = "quarkus.datasource.password")
    String dbPassword;

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String jdbcUrl;

    @ConfigProperty(name = "backup.mysqldump-path", defaultValue = "mysqldump")
    String mysqldumpPath;

    @ConfigProperty(name = "backup.mysql-path", defaultValue = "mysql")
    String mysqlPath;

    @ConfigProperty(name = "backup.mobile-sqlite.enabled", defaultValue = "true")
    boolean mobileSqliteEnabled;

    @ConfigProperty(name = "backup.mobile-sqlite.python.executable")
    Optional<String> mobileSqlitePython;

    @ConfigProperty(name = "backup.mobile-sqlite.script-path", defaultValue = "scripts/import_mysql_to_sqlite.py")
    String mobileSqliteScriptPath;

    public boolean isMobileSqliteEnabled() {
        return mobileSqliteEnabled;
    }

    public String extractDatabaseName() {
        // jdbc:mysql://localhost:3306/vena
        int slash = jdbcUrl.lastIndexOf('/');
        if (slash < 0 || slash == jdbcUrl.length() - 1) {
            return "vena";
        }
        String tail = jdbcUrl.substring(slash + 1);
        int q = tail.indexOf('?');
        return q >= 0 ? tail.substring(0, q) : tail;
    }

    public Path createDump(Path targetFile) throws Exception {
        Files.createDirectories(targetFile.getParent());
        String dbName = extractDatabaseName();

        String dumpExe = resolveMysqlTool(mysqldumpPath, "mysqldump");
        ProcessBuilder pb = new ProcessBuilder(
                dumpExe,
                "-h", "localhost",
                "-u", dbUser,
                "--password=" + dbPassword,
                "--single-transaction",
                "--routines",
                "--triggers",
                dbName
        );
        Process process = startMysqlProcess(pb, dumpExe);
        StringBuilder dumpErr = new StringBuilder();
        Thread errThread = new Thread(() -> appendStream(process.getErrorStream(), dumpErr), "mysqldump-stderr");
        errThread.setDaemon(true);
        errThread.start();

        try (var in = process.getInputStream()) {
            Files.copy(in, targetFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        errThread.join();

        int code = process.waitFor();
        if (dumpErr.length() > 0) {
            LOG.warnf("mysqldump: %s", dumpErr.toString().trim());
        }
        if (code != 0) {
            throw new IllegalStateException("mysqldump failed with exit code " + code + ": " + dumpErr);
        }
        if (!Files.exists(targetFile) || Files.size(targetFile) == 0) {
            throw new IllegalStateException("Backup file was not created or is empty");
        }
        return targetFile;
    }

    public void restoreFromDump(Path dumpFile) throws Exception {
        if (!Files.exists(dumpFile)) {
            throw new IllegalStateException("Backup file not found: " + dumpFile);
        }

        Path sqlFile = dumpFile;
        Path extracted = null;
        String name = dumpFile.getFileName().toString().toLowerCase();
        if (name.endsWith(".zip")) {
            extracted = extractSqlFromZip(dumpFile);
            sqlFile = extracted;
        }

        Path cleaned = null;
        try {
            cleaned = stripLeadingClientWarnings(sqlFile);
            if (!Files.exists(cleaned) || Files.size(cleaned) == 0) {
                throw new IllegalStateException("Backup file does not contain SQL");
            }
            restoreSqlFile(cleaned);
        } finally {
            if (cleaned != null && !cleaned.equals(sqlFile)) {
                Files.deleteIfExists(cleaned);
            }
            if (extracted != null) {
                Files.deleteIfExists(extracted);
            }
        }
    }

    /**
     * Older dumps were captured with stderr merged into the SQL file, so the first line is
     * {@code mysqldump: [Warning] Using a password...}. mysql then fails with ERROR 1064.
     * Returns a temp copy without those leading lines, or the original file when it is already SQL.
     */
    private Path stripLeadingClientWarnings(Path sqlFile) throws IOException {
        byte[] head = new byte[4096];
        int read;
        try (InputStream in = Files.newInputStream(sqlFile)) {
            read = in.read(head);
        }
        if (read <= 0) {
            return sqlFile;
        }
        int skip = leadingClientWarningBytes(head, read);
        if (skip <= 0) {
            return sqlFile;
        }

        Path cleaned = Files.createTempFile("kmc-restore-", ".sql");
        try (InputStream in = Files.newInputStream(sqlFile);
             OutputStream out = Files.newOutputStream(cleaned)) {
            long remaining = skip;
            byte[] buf = new byte[8192];
            while (remaining > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) {
                    break;
                }
                remaining -= n;
            }
            in.transferTo(out);
        }
        return cleaned;
    }

    private static int leadingClientWarningBytes(byte[] data, int length) {
        int index = 0;
        int skipped = 0;
        while (index < length) {
            int breakAt = indexOfLineBreak(data, index, length);
            if (breakAt < 0) {
                break;
            }
            String line = new String(data, index, breakAt - index, StandardCharsets.ISO_8859_1).trim();
            if (!line.isEmpty() && !isMysqlClientWarning(line)) {
                break;
            }
            int next = skipLineBreak(data, breakAt, length);
            skipped = next;
            index = next;
        }
        return skipped;
    }

    private static boolean isMysqlClientWarning(String line) {
        String lower = line.toLowerCase();
        return lower.startsWith("mysqldump:")
                || lower.startsWith("mysql:")
                || lower.contains("using a password on the command line interface can be insecure");
    }

    private static int indexOfLineBreak(byte[] data, int from, int length) {
        for (int i = from; i < length; i++) {
            if (data[i] == '\n' || data[i] == '\r') {
                return i;
            }
        }
        return -1;
    }

    private static int skipLineBreak(byte[] data, int breakAt, int length) {
        if (breakAt >= length) {
            return breakAt;
        }
        if (data[breakAt] == '\r') {
            int next = breakAt + 1;
            if (next < length && data[next] == '\n') {
                return next + 1;
            }
            return next;
        }
        return breakAt + 1;
    }

    private static void appendStream(InputStream in, StringBuilder out) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
        } catch (IOException ignored) {
            // Caller reports the process exit code.
        }
    }

    private void restoreSqlFile(Path sqlFile) throws Exception {
        String dbName = extractDatabaseName();

        String mysqlExe = resolveMysqlTool(mysqlPath, "mysql");
        ProcessBuilder pb = new ProcessBuilder(
                mysqlExe,
                "-h", "localhost",
                "-u", dbUser,
                "--password=" + dbPassword,
                dbName
        );
        pb.redirectInput(sqlFile.toFile());
        pb.redirectErrorStream(true);
        Process process = startMysqlProcess(pb, mysqlExe);

        StringBuilder err = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                err.append(line).append('\n');
            }
        }

        int code = process.waitFor();
        if (code != 0) {
            throw new IllegalStateException("mysql restore failed: " + err);
        }
    }

    /** Unzip and return backup.sql if present, otherwise the first .sql entry. */
    private Path extractSqlFromZip(Path zipFile) throws Exception {
        Path preferred = null;
        Path firstSql = null;

        try (java.util.zip.ZipInputStream zis =
                     new java.util.zip.ZipInputStream(Files.newInputStream(zipFile))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName().replace('\\', '/');
                String base = entryName.substring(entryName.lastIndexOf('/') + 1).toLowerCase();
                if (!base.endsWith(".sql")) {
                    continue;
                }
                Path target = Files.createTempFile("kmc-entry-", ".sql");
                Files.copy(zis, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                if ("backup.sql".equals(base)) {
                    if (preferred != null) {
                        Files.deleteIfExists(preferred);
                    }
                    preferred = target;
                } else if (firstSql == null) {
                    firstSql = target;
                } else {
                    Files.deleteIfExists(target);
                }
            }
        }

        Path chosen = preferred != null ? preferred : firstSql;
        if (chosen == null) {
            throw new IllegalStateException("No .sql file found inside zip: " + zipFile);
        }
        if (preferred != null && firstSql != null && !preferred.equals(firstSql)) {
            Files.deleteIfExists(firstSql);
        }
        if (Files.size(chosen) == 0) {
            Files.deleteIfExists(chosen);
            throw new IllegalStateException("Extracted SQL dump is empty");
        }
        return chosen;
    }

    public Path buildLatestLocalPath(Path facilityDir) {
        return facilityDir.resolve(buildLatestBackupFileName());
    }

    public String buildLatestBackupFileName() {
        return extractDatabaseName() + ".sql";
    }

    public String buildDropboxBackupFileName() {
        return extractDatabaseName() + ".zip";
    }

    public String buildMobileSqliteFileName() {
        return MOBILE_SQLITE_FILE_NAME;
    }

    public Path buildLatestMobileSqlitePath(Path facilityDir) {
        return facilityDir.resolve(MOBILE_SQLITE_FILE_NAME);
    }

    /** Convert a MySQL dump to mother-app SQLite ({@code kmc-latest.db}). */
    public void exportMobileSqliteFromDump(Path sqlDump, Path outputDb, String dropboxPath) throws Exception {
        if (!mobileSqliteEnabled) {
            throw new IllegalStateException("Mobile SQLite export is disabled");
        }
        if (!Files.exists(sqlDump) || Files.size(sqlDump) == 0) {
            throw new IllegalStateException("SQL dump is missing or empty: " + sqlDump);
        }

        Path script = resolveMobileSqliteScriptPath();
        if (!Files.isRegularFile(script)) {
            throw new IllegalStateException("Mobile SQLite script not found: " + script);
        }

        String python = resolveMobileSqlitePython();
        List<String> command = new ArrayList<>();
        command.add(python);
        command.add(script.toAbsolutePath().toString());
        command.add(sqlDump.toAbsolutePath().toString());
        command.add(outputDb.toAbsolutePath().toString());
        if (dropboxPath != null && !dropboxPath.isBlank()) {
            command.add("--dropbox-path");
            command.add(dropboxPath);
        }

        LOG.infof("Exporting mobile SQLite: %s -> %s", sqlDump, outputDb);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }

        int code = process.waitFor();
        if (code != 0) {
            throw new IllegalStateException(
                    "Mobile SQLite export failed (code " + code + "): " + output);
        }

        if (!Files.exists(outputDb) || Files.size(outputDb) == 0) {
            throw new IllegalStateException("Mobile SQLite file was not created or is empty: " + outputDb);
        }

        LOG.infof("Mobile SQLite export completed: %s (%d bytes). %s",
                outputDb, Files.size(outputDb), output.toString().trim());
    }

    private Path resolveMobileSqliteScriptPath() {
        Path configured = Path.of(mobileSqliteScriptPath);
        if (configured.isAbsolute() && Files.isRegularFile(configured)) {
            return configured;
        }

        Path relative = Path.of(System.getProperty("user.dir", ".")).resolve(mobileSqliteScriptPath);
        if (Files.isRegularFile(relative)) {
            return relative;
        }

        Path parent = Path.of(System.getProperty("user.dir", ".")).getParent();
        if (parent != null) {
            Path parentRelative = parent.resolve(mobileSqliteScriptPath);
            if (Files.isRegularFile(parentRelative)) {
                return parentRelative;
            }
        }

        Path motherMobileScript = Path.of(System.getProperty("user.dir", "."))
                .resolve("../../../../mother mobile/tools/mysql_port/import_mysql_to_sqlite.py")
                .normalize();
        if (Files.isRegularFile(motherMobileScript)) {
            return motherMobileScript;
        }

        return relative;
    }

    private String resolveMobileSqlitePython() throws Exception {
        if (mobileSqlitePython.isPresent() && !mobileSqlitePython.get().isBlank()) {
            return mobileSqlitePython.get().trim();
        }

        String env = System.getenv("MOBILE_SQLITE_PYTHON");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }

        for (String candidate : List.of("python", "python3")) {
            if (isPythonRunnable(candidate)) {
                return candidate;
            }
        }

        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            String onPath = findPythonOnWindowsPath();
            if (onPath != null) {
                return onPath;
            }
        }

        throw new IllegalStateException(
                "Python not found for mobile SQLite export. Install Python 3 or set "
                        + "backup.mobile-sqlite.python.executable / MOBILE_SQLITE_PYTHON.");
    }

    private static boolean isPythonRunnable(String command) {
        try {
            Process process = new ProcessBuilder(command, "--version").start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String findPythonOnWindowsPath() {
        try {
            Process process = new ProcessBuilder("where.exe", "python").start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && Files.isRegularFile(Path.of(line))) {
                        return line;
                    }
                }
            }
            process.waitFor();
        } catch (Exception ignored) {
            // best-effort
        }
        return null;
    }

    /**
     * Windows services often have no user PATH, so {@code mysqldump} by name fails with
     * CreateProcess error=2. Use a configured full path, then the usual install folders.
     */
    private String resolveMysqlTool(String configured, String toolName) {
        String value = configured == null || configured.isBlank() ? toolName : configured.trim();
        if (looksLikeFilePath(value)) {
            Path direct = Path.of(value);
            if (!Files.isRegularFile(direct)) {
                throw new IllegalStateException(
                        toolName + " was not found at " + value
                                + ". Set backup." + configKey(toolName) + " to the full path of " + toolName + ".exe");
            }
            return direct.toAbsolutePath().toString();
        }
        if (isWindows()) {
            String located = locateWindowsMysqlTool(toolName);
            if (located != null) {
                LOG.infof("Resolved %s to %s", toolName, located);
                return located;
            }
            throw new IllegalStateException(
                    toolName + " was not found. Install MySQL Server, or set backup."
                            + configKey(toolName)
                            + " to the full path of " + toolName + ".exe. Example: "
                            + "C:/Program Files/MySQL/MySQL Server 9.0/bin/" + toolName + ".exe");
        }
        return value;
    }

    private static Process startMysqlProcess(ProcessBuilder pb, String executable) throws IOException {
        try {
            return pb.start();
        } catch (IOException e) {
            throw new IOException(
                    "Cannot run \"" + executable + "\": " + e.getMessage()
                            + ". Install MySQL client tools or set the full path in backup settings.",
                    e);
        }
    }

    private static String configKey(String toolName) {
        return "mysql".equals(toolName) ? "mysql-path" : "mysqldump-path";
    }

    private static boolean looksLikeFilePath(String value) {
        return value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 || value.indexOf(':') >= 0;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String locateWindowsMysqlTool(String toolName) {
        String fileName = toolName.toLowerCase().endsWith(".exe") ? toolName : toolName + ".exe";
        String onPath = findExecutableOnWindowsPath(fileName);
        if (onPath != null) {
            return onPath;
        }

        String mysqlHome = System.getenv("MYSQL_HOME");
        if (mysqlHome != null && !mysqlHome.isBlank()) {
            Path homeBin = Path.of(mysqlHome.trim(), "bin", fileName);
            if (Files.isRegularFile(homeBin)) {
                return homeBin.toString();
            }
        }

        List<Path> programRoots = new ArrayList<>();
        addExistingDirectory(programRoots, System.getenv("ProgramW6432"));
        addExistingDirectory(programRoots, System.getenv("ProgramFiles"));
        addExistingDirectory(programRoots, System.getenv("ProgramFiles(x86)"));

        for (Path root : programRoots) {
            String underMysql = firstToolUnder(root.resolve("MySQL"), fileName);
            if (underMysql != null) {
                return underMysql;
            }
            String maria = firstNamedInstall(root, "mariadb", fileName);
            if (maria != null) {
                return maria;
            }
        }

        for (Path candidate : List.of(
                Path.of("C:/xampp/mysql/bin", fileName),
                Path.of("C:/laragon/bin/mysql", fileName))) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }
        String laragon = firstToolUnder(Path.of("C:/laragon/bin/mysql"), fileName);
        return laragon;
    }

    private static void addExistingDirectory(List<Path> roots, String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        Path dir = Path.of(path.trim());
        if (Files.isDirectory(dir) && !roots.contains(dir)) {
            roots.add(dir);
        }
    }

    /** Highest-named child folder that contains bin/tool, for example MySQL Server 9.0. */
    private static String firstToolUnder(Path parent, String fileName) {
        if (!Files.isDirectory(parent)) {
            return null;
        }
        try (Stream<Path> children = Files.list(parent)) {
            return children
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                    .map(dir -> dir.resolve("bin").resolve(fileName))
                    .filter(Files::isRegularFile)
                    .map(Path::toString)
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static String firstNamedInstall(Path programFiles, String namePrefix, String fileName) {
        if (!Files.isDirectory(programFiles)) {
            return null;
        }
        try (Stream<Path> children = Files.list(programFiles)) {
            return children
                    .filter(Files::isDirectory)
                    .filter(dir -> dir.getFileName().toString().toLowerCase().startsWith(namePrefix))
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                    .map(dir -> dir.resolve("bin").resolve(fileName))
                    .filter(Files::isRegularFile)
                    .map(Path::toString)
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static String findExecutableOnWindowsPath(String fileName) {
        String bare = fileName.toLowerCase().endsWith(".exe")
                ? fileName.substring(0, fileName.length() - 4)
                : fileName;
        try {
            Process process = new ProcessBuilder("where.exe", bare).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && Files.isRegularFile(Path.of(line))) {
                        process.waitFor();
                        return line;
                    }
                }
            }
            process.waitFor();
        } catch (Exception ignored) {
            // best-effort
        }
        return null;
    }

    /** Zip a SQL dump for Dropbox upload (single entry named like vena.sql). */
    public Path zipSqlDump(Path sqlFile) throws Exception {
        if (!Files.exists(sqlFile) || Files.size(sqlFile) == 0) {
            throw new IllegalStateException("SQL dump is missing or empty: " + sqlFile);
        }
        Path zipFile = Files.createTempFile("backup-upload-", ".zip");
        String entryName = sqlFile.getFileName().toString();
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            ZipEntry entry = new ZipEntry(entryName);
            zos.putNextEntry(entry);
            Files.copy(sqlFile, zos);
            zos.closeEntry();
        }
        if (!Files.exists(zipFile) || Files.size(zipFile) == 0) {
            Files.deleteIfExists(zipFile);
            throw new IllegalStateException("Zip archive was not created or is empty");
        }
        return zipFile;
    }

    /** Remove older timestamped dumps; only the single latest file is kept. */
    public void removeStaleLocalBackups(Path facilityDir) throws Exception {
        if (!Files.isDirectory(facilityDir)) {
            return;
        }
        Path latestSql = buildLatestLocalPath(facilityDir);
        Path latestMobile = buildLatestMobileSqlitePath(facilityDir);
        try (Stream<Path> entries = Files.list(facilityDir)) {
            entries.filter(Files::isRegularFile)
                    .filter(p -> !p.equals(latestSql) && !p.equals(latestMobile))
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.startsWith("kmc-")
                                || name.endsWith(".sql")
                                || name.endsWith(".zip")
                                || name.endsWith(".db");
                    })
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                            // best-effort cleanup
                        }
                    });
        }
    }
}
