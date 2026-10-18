package cl.digitalfix.report.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import static java.nio.file.StandardOpenOption.*;

@Component
public class RegistroPersistente implements AutoCloseable {
    private final Path directorio;
    private final FileChannel canal;
    private final FileLock bloqueo;

    public RegistroPersistente(@Value("${report.storage}") String ruta) throws IOException {
        directorio = Path.of(ruta).toAbsolutePath();
        Files.createDirectories(directorio);
        restringir(directorio, "rwx------");
        canal = FileChannel.open(directorio.resolve("report.lock"), CREATE, WRITE);
        try { bloqueo = canal.tryLock(); }
        catch (IOException | RuntimeException e) { canal.close(); throw e; }
        if (bloqueo == null) { canal.close(); throw new IOException("El volumen ya está en uso"); }
    }

    public Path documento(UUID id) { return directorio.resolve(id + ".pdf"); }

    public boolean completado(UUID id, String huella) throws IOException {
        Path identidad = directorio.resolve(id + ".identidad");
        if (Files.exists(identidad)) {
            if (!Files.readString(identidad).equals(huella))
                throw new ErrorPermanente("identidad_conflictiva");
        } else {
            // No adoptar archivos huérfanos cuya identidad no podemos verificar.
            if (Files.exists(documento(id)) || Files.exists(directorio.resolve(id + ".ok")))
                throw new ErrorPermanente("registro_sin_identidad");
            escribirAtomico(identidad, huella.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        Path exito = directorio.resolve(id + ".ok");
        if (!Files.exists(exito)) return false;
        if (!Files.isRegularFile(documento(id)) || !Files.readString(exito).equals(sha256(Files.readAllBytes(documento(id)))))
            throw new ErrorPermanente("documento_completado_ausente_o_alterado");
        return true;
    }

    public void completar(UUID id) throws IOException {
        escribirAtomico(directorio.resolve(id + ".ok"),
                sha256(Files.readAllBytes(documento(id))).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] contenido) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contenido)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public static void escribirAtomico(Path destino, byte[] contenido) throws IOException {
        Path temporal = Files.createTempFile(destino.getParent(), ".report-", ".tmp");
        try {
            restringir(temporal, "rw-------");
            try (var salida = FileChannel.open(temporal, WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(contenido);
                while (buffer.hasRemaining()) salida.write(buffer);
                salida.force(true);
            }
            Files.move(temporal, destino, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            // En Linux persiste también la entrada de directorio tras el rename.
            if (destino.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                try (var padre = FileChannel.open(destino.getParent(), READ)) { padre.force(true); }
            }
        } finally { Files.deleteIfExists(temporal); }
    }

    private static void restringir(Path archivo, String permisos) throws IOException {
        if (archivo.getFileSystem().supportedFileAttributeViews().contains("posix"))
            Files.setPosixFilePermissions(archivo, PosixFilePermissions.fromString(permisos));
    }

    @Override @PreDestroy
    public void close() throws IOException {
        if (canal.isOpen()) { bloqueo.release(); canal.close(); }
    }
}
