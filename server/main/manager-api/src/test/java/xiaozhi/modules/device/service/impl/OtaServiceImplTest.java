package xiaozhi.modules.device.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import xiaozhi.common.exception.RenException;

class OtaServiceImplTest {
    @Test
    void repeatedUploadsUseUniqueTraversalSafeManagedPaths() {
        OtaServiceImpl service = new OtaServiceImpl();
        MockMultipartFile file = new MockMultipartFile("file", "../firmware.bin", "application/octet-stream",
                new byte[] { 1, 2, 3 });

        String first = service.storeFirmware(file);
        String second = service.storeFirmware(file);
        try {
            assertNotEquals(first, second);
            assertTrue(first.matches("uploadfile[/\\\\][0-9a-f-]+\\.bin"));
            assertTrue(second.matches("uploadfile[/\\\\][0-9a-f-]+\\.bin"));
        } finally {
            service.deleteFirmwareFile(first);
            service.deleteFirmwareFile(second);
        }
    }

    @Test
    void oversizedFirmwareIsRejectedBeforeReadingOrWriting() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(100L * 1024 * 1024 + 1);

        assertThrows(RenException.class, () -> new OtaServiceImpl().storeFirmware(file));
    }

    @Test
    void unmanagedDeletePathIsSkipped() {
        assertDoesNotThrow(() -> new OtaServiceImpl().deleteFirmwareFile("../outside.bin"));
    }

    @Test
    void managedFileResolutionRejectsAbsoluteTraversalAndSymlinkEscapes() throws Exception {
        OtaServiceImpl service = new OtaServiceImpl();
        Path directory = Path.of("uploadfile").toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Path link = directory.resolve("escape-" + System.nanoTime() + ".bin");
        try {
            Files.createSymbolicLink(link, Path.of("/etc/hosts"));

            assertRejectedResolution(service, "/etc/hosts");
            assertRejectedResolution(service, "../etc/hosts");
            assertRejectedResolution(service, Path.of("uploadfile").resolve(link.getFileName()).toString());
        } finally {
            Files.deleteIfExists(link);
        }
    }

    @Test
    void failedCopyRemovesThePartiallyWrittenFirmware() throws Exception {
        OtaServiceImpl service = new OtaServiceImpl();
        Path directory = Path.of("uploadfile").toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Set<String> before = regularFiles(directory);
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(3L);
        when(file.getOriginalFilename()).thenReturn("broken.bin");
        when(file.getInputStream()).thenReturn(new InputStream() {
            private int reads;

            @Override
            public int read() throws IOException {
                if (reads++ == 0) return 1;
                throw new IOException("stream interrupted");
            }
        });

        assertThrows(RenException.class, () -> service.storeFirmware(file));

        assertEquals(before, regularFiles(directory));
    }

    private void assertRejectedResolution(OtaServiceImpl service, String path) throws Exception {
        var method = OtaServiceImpl.class.getMethod("resolveManagedFirmwareFile", String.class);
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> method.invoke(service, path));
        assertTrue(failure.getCause() instanceof RenException);
    }

    private Set<String> regularFiles(Path directory) throws IOException {
        try (var paths = Files.list(directory)) {
            return paths.filter(Files::isRegularFile).map(path -> path.getFileName().toString())
                    .collect(Collectors.toSet());
        }
    }
}
