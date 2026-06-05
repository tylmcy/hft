package com.hft.trading.snapshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 快照文件工具类
 *
 * 提供 MD5 校验和计算、验证，以及临时文件检测恢复功能
 * 用于保证 RPO=0 的快照持久化机制
 */
public final class SnapshotFileUtils {

    private static final Logger log = LoggerFactory.getLogger(SnapshotFileUtils.class);

    // 快照文件名模式: {type}_snapshot_{index}.dat
    private static final Pattern SNAPSHOT_PATTERN = Pattern.compile("(.+)_snapshot_(\\d+)\\.dat");

    private SnapshotFileUtils() {
        // 工具类，禁止实例化
    }

    /**
     * 计算文件的 MD5 校验和
     *
     * @param filePath 文件路径
     * @return MD5 校验和（32位小写十六进制字符串）
     * @throws IOException 文件读取失败
     */
    public static String calculateMD5(String filePath) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }

        byte[] fileBytes = Files.readAllBytes(Paths.get(filePath));
        byte[] hashBytes = md.digest(fileBytes);

        StringBuilder sb = new StringBuilder(32);
        for (byte b : hashBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 验证文件的校验和
     *
     * @param dataFile 数据文件路径
     * @param checksumFile 校验和文件路径
     * @return true 如果校验和匹配，false 如果不匹配或文件不存在
     */
    public static boolean verifyChecksum(String dataFile, String checksumFile) {
        Path dataPath = Paths.get(dataFile);
        Path checksumPath = Paths.get(checksumFile);

        if (!Files.exists(dataPath) || !Files.exists(checksumPath)) {
            return false;
        }

        try {
            String actualMd5 = calculateMD5(dataFile);
            String expectedMd5 = Files.readString(checksumPath).trim();
            return actualMd5.equals(expectedMd5);
        } catch (IOException e) {
            log.warn("Failed to verify checksum for {}: {}", dataFile, e.getMessage());
            return false;
        }
    }

    /**
     * 检测并恢复临时文件
     *
     * 处理规则：
     * 1. 如果存在 .tmp 文件且对应的 .dat 文件也存在，删除 .tmp 文件
     * 2. 如果存在 .tmp 文件但 .dat 不存在：
     *    - 如果 .tmp 校验和有效，重命名为 .dat
     *    - 如果 .tmp 校验和无效或无校验和文件，删除 .tmp 文件
     *
     * @param snapshotDir 快照目录
     * @param type 快照类型（如 "account", "orderbook"）
     */
    public static void detectAndRecoverTmpFiles(String snapshotDir, String type) {
        Path dir = Paths.get(snapshotDir);
        if (!Files.exists(dir)) {
            return;
        }

        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".tmp"))
                 .filter(p -> p.getFileName().toString().startsWith(type + "_snapshot_"))
                 .forEach(tmpPath -> {
                     String tmpName = tmpPath.getFileName().toString();
                     String datName = tmpName.substring(0, tmpName.length() - 4); // 去掉 .tmp
                     Path datPath = dir.resolve(datName);

                     String tmpChecksumName = tmpName + ".md5";
                     Path tmpChecksumPath = dir.resolve(tmpChecksumName);

                     if (Files.exists(datPath)) {
                         // .dat 已存在，删除 .tmp
                         deleteFile(tmpPath, "tmp file with existing dat");
                         deleteFile(tmpChecksumPath, "tmp checksum file");
                     } else {
                         // .dat 不存在，检查 .tmp 校验和
                         if (verifyChecksum(tmpPath.toString(), tmpChecksumPath.toString())) {
                             // 校验和有效，重命名
                             renameFile(tmpPath, datPath);
                             // 重命名校验和文件
                             String datChecksumName = datName + ".md5";
                             Path datChecksumPath = dir.resolve(datChecksumName);
                             renameFile(tmpChecksumPath, datChecksumPath);
                         } else {
                             // 校验和无效，删除
                             deleteFile(tmpPath, "tmp file with invalid checksum");
                             deleteFile(tmpChecksumPath, "invalid tmp checksum file");
                         }
                     }
                 });
        } catch (IOException e) {
            log.error("Failed to detect tmp files in {}: {}", snapshotDir, e.getMessage());
        }
    }

    /**
     * 查找最新有效快照
     *
     * @param snapshotDir 快照目录
     * @param type 快照类型（如 "account", "orderbook"）
     * @return 最新有效快照信息，如果没有则返回 empty
     */
    public static Optional<SnapshotInfo> findLatestValidSnapshot(String snapshotDir, String type) {
        Path dir = Paths.get(snapshotDir);
        if (!Files.exists(dir)) {
            return Optional.empty();
        }

        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".dat"))
                        .filter(p -> p.getFileName().toString().startsWith(type + "_snapshot_"))
                        .filter(p -> {
                            String checksumName = p.getFileName().toString() + ".md5";
                            Path checksumPath = dir.resolve(checksumName);
                            return verifyChecksum(p.toString(), checksumPath.toString());
                        })
                        .map(p -> parseSnapshotInfo(p, type))
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .max(Comparator.comparingLong(SnapshotInfo::getIndex));
        } catch (IOException e) {
            log.error("Failed to find latest snapshot in {}: {}", snapshotDir, e.getMessage());
            return Optional.empty();
        }
    }

    private static Optional<SnapshotInfo> parseSnapshotInfo(Path filePath, String expectedType) {
        String fileName = filePath.getFileName().toString();
        Matcher matcher = SNAPSHOT_PATTERN.matcher(fileName);
        if (matcher.matches()) {
            String type = matcher.group(1);
            if (type.equals(expectedType)) {
                long index = Long.parseLong(matcher.group(2));
                return Optional.of(new SnapshotInfo(index, filePath.toString()));
            }
        }
        return Optional.empty();
    }

    private static void deleteFile(Path path, String description) {
        try {
            if (Files.exists(path)) {
                Files.delete(path);
                log.info("Deleted {}: {}", description, path);
            }
        } catch (IOException e) {
            log.warn("Failed to delete {}: {}", description, path, e);
        }
    }

    private static void renameFile(Path source, Path target) {
        try {
            Files.move(source, target);
            log.info("Renamed {} to {}", source, target);
        } catch (IOException e) {
            log.warn("Failed to rename {} to {}: {}", source, target, e.getMessage());
        }
    }

    /**
     * 快照信息
     */
    public static class SnapshotInfo {
        private final long index;
        private final String filePath;

        public SnapshotInfo(long index, String filePath) {
            this.index = index;
            this.filePath = filePath;
        }

        public long getIndex() {
            return index;
        }

        public String getFilePath() {
            return filePath;
        }
    }
}
