package com.yawac.client.data;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Only the cache directory supplied by the app is traversed; symbolic links are not followed. */
public final class CacheFiles {
    private CacheFiles() { }
    public static long size(File root) throws IOException {
        if (!root.exists()) return 0;
        try (Stream<Path> paths = Files.walk(root.toPath())) {
            long total = 0;
            for (Path path : paths.collect(Collectors.toList())) {
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) total += Files.size(path);
            }
            return total;
        }
    }
    public static void clear(File root) throws IOException {
        if (!root.exists()) return;
        Path base = root.toPath();
        List<Path> entries;
        try (Stream<Path> paths = Files.walk(base)) {
            entries = paths.filter(path -> !path.equals(base)).sorted(Comparator.reverseOrder()).collect(Collectors.toList());
        }
        for (Path path : entries) Files.deleteIfExists(path);
    }
}
