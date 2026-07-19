package kim.mingyo.browserssh.terminal;

public record DownloadInspection(
        Kind kind,
        String remotePath,
        String downloadName,
        long sizeBytes,
        long fileCount
) {
    public enum Kind {
        FILE,
        DIRECTORY
    }
}
