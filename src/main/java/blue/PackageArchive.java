package blue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.ZipFile;

/** Streaming bounds precede buffer growth. One reader for folders, archives and resources. */
final class PackageArchive {
  final PackageLimits limits;
  long expanded;
  int entries;
  final Set<String> names = new HashSet<>();

  PackageArchive(PackageLimits limits) {
    this.limits = limits;
  }

  void bound(boolean valid, String detail) {
    if (!valid) throw new PackageException("OperationLimitExceeded", detail);
  }

  void entry(String name) {
    PackagePaths.safe(name);
    bound(++entries <= limits.entries(), "package entries");
    PackageIntegrity.require(
        names.add(PackagePaths.fold(name)),
        "InvalidPackage",
        "Duplicate or ambiguous path: " + name);
  }

  byte[] content(InputStream in, long declared) throws IOException {
    if (declared >= 0)
      bound(
          declared <= limits.entryBytes() && declared <= limits.expandedBytes() - expanded,
          "package entry bytes");
    var bytes = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    long size = 0;
    for (int count; (count = in.read(buffer)) != -1; ) {
      size += count;
      expanded += count;
      bound(
          size <= limits.entryBytes() && expanded <= limits.expandedBytes(),
          "package expanded bytes");
      bytes.write(buffer, 0, count);
    }
    byte[] result = bytes.toByteArray();
    String text = PackageIntegrity.utf8(result);
    PackageIntegrity.check(
        !text.startsWith("\uFEFF") && text.indexOf('\r') < 0,
        "Package text must use UTF-8/LF without BOM");
    return result;
  }

  Map<String, byte[]> read(Path location) throws IOException {
    PackageIntegrity.require(!Files.isSymbolicLink(location), "InvalidPackage", "symlink root");
    if (!Files.isDirectory(location, LinkOption.NOFOLLOW_LINKS)) {
      bound(Files.size(location) <= limits.archiveBytes(), "package archive bytes");
      // ZipFile requires a valid central directory; truncated archives cannot look complete.
      archiveMetadata(location);
      var files = new TreeMap<String, byte[]>();
      try (var zip = new ZipFile(location.toFile(), StandardCharsets.UTF_8)) {
        var iterator = zip.entries();
        while (iterator.hasMoreElements()) {
          var item = iterator.nextElement();
          String name = item.isDirectory() ? item.getName().replaceAll("/$", "") : item.getName();
          entry(name);
          PackageIntegrity.check(!item.isDirectory(), "Directory ZIP entry forbidden");
          PackagePaths.allowed(name);
          try (var input = zip.getInputStream(item)) {
            byte[] body = content(input, item.getSize());
            var crc = new CRC32();
            crc.update(body);
            PackageIntegrity.require(
                crc.getValue() == item.getCrc(), "InvalidPackage", "ZIP CRC mismatch");
            files.put(name, body);
          }
        }
      }
      return files;
    }
    Path root = location.toRealPath();
    var files = new TreeMap<String, byte[]>();
    try (var paths = Files.walk(root)) {
      var iterator = paths.iterator();
      while (iterator.hasNext()) {
        Path path = iterator.next();
        if (path.equals(root)) continue;
        PackageIntegrity.require(!Files.isSymbolicLink(path), "InvalidPackage", "symlink member");
        String name = root.relativize(path).toString().replace(File.separatorChar, '/');
        if (name.equals("package.bluepkg")) continue;
        entry(name);
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
        PackageIntegrity.require(
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS),
            "InvalidPackage",
            "Not a regular file");
        PackagePaths.allowed(name);
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
          files.put(name, content(input, Files.size(path)));
        }
      }
    }
    return files;
  }

  /** Inspect the bounded central directory; ZipFile does not expose Unix member modes. */
  private void archiveMetadata(Path path) throws IOException {
    try (var file = new RandomAccessFile(path.toFile(), "r")) {
      int tailSize = (int) Math.min(file.length(), 65557L);
      byte[] tail = new byte[tailSize];
      file.seek(file.length() - tailSize);
      file.readFully(tail);
      int end = -1;
      for (int i = tail.length - 22; i >= 0; i--) {
        if (u32(tail, i) == 0x06054b50L && i + 22 + u16(tail, i + 20) == tail.length) {
          end = i;
          break;
        }
      }
      PackageIntegrity.require(end >= 0, "InvalidPackage", "Missing ZIP central directory");
      PackageIntegrity.require(
          u16(tail, end + 4) == 0
              && u16(tail, end + 6) == 0
              && u16(tail, end + 8) == u16(tail, end + 10),
          "InvalidPackage",
          "Multi-volume ZIP forbidden");
      int count = u16(tail, end + 10);
      long size = u32(tail, end + 12), offset = u32(tail, end + 16);
      bound(count <= limits.entries(), "package entries");
      PackageIntegrity.require(
          count != 65535 && size != 0xffffffffL && offset != 0xffffffffL,
          "InvalidPackage",
          "ZIP64 exceeds supported package envelope");
      PackageIntegrity.require(
          offset + size == file.length() - tailSize + end,
          "InvalidPackage",
          "Invalid ZIP directory extent");
      file.seek(offset);
      byte[] header = new byte[46];
      for (int n = 0; n < count; n++) {
        file.readFully(header);
        PackageIntegrity.require(
            u32(header, 0) == 0x02014b50L, "InvalidPackage", "Invalid ZIP entry");
        long mode = u32(header, 38) >>> 16;
        long kind = mode & 0170000;
        PackageIntegrity.require(
            (kind == 0 || kind == 0100000) && (mode & 0111) == 0,
            "InvalidPackage",
            "Links, directories and executable ZIP entries forbidden");
        PackageIntegrity.require(
            (u16(header, 8) & 1) == 0 && (u32(header, 38) & 16) == 0,
            "InvalidPackage",
            "Encrypted or directory ZIP entry forbidden");
        byte[] name = new byte[u16(header, 28)];
        file.readFully(name);
        PackageIntegrity.utf8(name);
        long next = file.getFilePointer() + u16(header, 30) + u16(header, 32);
        checkLocalHeader(file, header, name, offset);
        file.seek(next);
        PackageIntegrity.require(
            file.getFilePointer() <= offset + size,
            "InvalidPackage",
            "ZIP entry exceeds directory");
      }
      PackageIntegrity.require(
          file.getFilePointer() == offset + size, "InvalidPackage", "ZIP directory count mismatch");
    }
  }

  /** The same member must be described by both ZIP headers. */
  private static void checkLocalHeader(
      RandomAccessFile file, byte[] central, byte[] name, long directory) throws IOException {
    long offset = u32(central, 42);
    PackageIntegrity.check(offset + 30 <= directory, "Invalid local ZIP header offset");
    file.seek(offset);
    byte[] local = new byte[30];
    file.readFully(local);
    PackageIntegrity.check(
        u32(local, 0) == 0x04034b50L
            && u16(local, 6) == u16(central, 8)
            && u16(local, 8) == u16(central, 10)
            && u16(local, 26) == name.length,
        "Conflicting local ZIP header");
    byte[] localName = new byte[name.length];
    file.readFully(localName);
    PackageIntegrity.check(Arrays.equals(name, localName), "Conflicting ZIP member names");
    PackageIntegrity.check(
        offset + 30 + name.length + u16(local, 28) + u32(central, 20) <= directory,
        "ZIP member overlaps directory");
    if ((u16(local, 6) & 8) == 0) {
      PackageIntegrity.check(
          u32(local, 14) == u32(central, 16)
              && u32(local, 18) == u32(central, 20)
              && u32(local, 22) == u32(central, 24),
          "Conflicting ZIP member sizes or checksum");
    }
  }

  private static int u16(byte[] b, int p) {
    return (b[p] & 255) | (b[p + 1] & 255) << 8;
  }

  private static long u32(byte[] b, int p) {
    return (long) u16(b, p) | (long) u16(b, p + 2) << 16;
  }
}
