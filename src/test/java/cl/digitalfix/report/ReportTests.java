package cl.digitalfix.report;

import cl.digitalfix.report.dto.Contrato.*;
import cl.digitalfix.report.messaging.Consumidor;
import cl.digitalfix.report.service.*;
import com.rabbitmq.client.Channel;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.core.*;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportTests {
    @TempDir Path dir;
    RegistroPersistente registro;
    InformePdf pdf;
    Espera espera;
    Procesador procesador;
    ValidatorFactory validadores;
    final JsonMapper json = JsonMapper.builder().build();
    final UUID id = UUID.randomUUID(), trace = UUID.randomUUID();
    final Instant inicio = Instant.parse("2026-10-15T12:00:00Z"), cierre = Instant.parse("2026-10-17T18:00:00Z");
    String fuente() {
        return Files.exists(Path.of("C:/Windows/Fonts/arial.ttf")) ? "C:/Windows/Fonts/arial.ttf"
                : "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf";
    }
    @BeforeEach void preparar() throws Exception {
        validadores = Validation.buildDefaultValidatorFactory();
        registro = spy(new RegistroPersistente(dir.toString()));
        pdf = spy(new InformePdf(fuente())); espera = mock(Espera.class);
        procesador = nuevo();
    }
    Procesador nuevo() { return new Procesador(validadores.getValidator(), registro, pdf, espera); }
    @AfterEach void cerrar() throws Exception { registro.close(); validadores.close(); }
    Orden orden(String descripcion, List<Repuesto> repuestos) {
        return new Orden(15L,"CERRADA","oid-solicitante","tecnico-01",2L,descripcion,
                "Avenida Ñuñoa 123, Valparaíso",inicio,cierre,repuestos);
    }
    Sobre sobre(Orden orden) { return new Sobre("1.0","GENERAR_REPORTE",id,cierre,trace,"15",orden); }
    byte[] mensaje() { return json.writeValueAsBytes(sobre(orden("Revisión eléctrica",List.of(new Repuesto(4L,2))))); }
    void recibir(byte[] cuerpo, Channel canal) throws Exception {
        var props = new MessageProperties(); props.setDeliveryTag(7);
        new Consumidor(procesador).recibir(new Message(cuerpo,props),canal);
    }

    @Test void pdfRealConAcentosContenidoYVariasPaginas() throws Exception {
        List<Repuesto> repuestos = new ArrayList<>();
        for (long i=1; i<=120; i++) repuestos.add(new Repuesto(i,2));
        String descripcion = "Revisión eléctrica: instalación en Ñuñoa. ".repeat(20);
        procesador.procesar(json.writeValueAsBytes(sobre(orden(descripcion,repuestos))));
        try (var documento = Loader.loadPDF(registro.documento(id).toFile())) {
            assertTrue(documento.getNumberOfPages() >= 4);
            String texto = new PDFTextStripper().getText(documento);
            for (String requerido : List.of("DigitalFix", "CERRADA", "Revisión eléctrica", "Ñuñoa", "Valparaíso",
                    "oid-solicitante", "tecnico-01", "Repuesto ID 120", "Cantidad: 2", id.toString(), inicio.toString()))
                assertTrue(texto.contains(requerido), requerido);
        }
        assertTrue(Files.exists(dir.resolve(id+".ok")));
    }
    @Test void aceptaListaDeRepuestosVacia() throws Exception {
        procesador.procesar(json.writeValueAsBytes(sobre(orden("Sin repuestos",List.of()))));
        try (var d = Loader.loadPDF(registro.documento(id).toFile())) {
            assertTrue(new PDFTextStripper().getText(d).contains("Sin repuestos registrados"));
        }
    }
    @Test void deduplicaInclusoTrasReabrirRegistroSinReescribirPdf() throws Exception {
        procesador.procesar(mensaje());
        byte[] bytes = Files.readAllBytes(registro.documento(id));
        var fecha = Files.getLastModifiedTime(registro.documento(id));
        registro.close(); registro = new RegistroPersistente(dir.toString()); procesador = nuevo();
        procesador.procesar(mensaje());
        assertArrayEquals(bytes,Files.readAllBytes(registro.documento(id)));
        assertEquals(fecha,Files.getLastModifiedTime(registro.documento(id)));
        verify(pdf,times(1)).generarSiFalta(any(),any());
    }
    @Test void identidadSeCalculaSobreDatosNoEspaciadoJson() throws Exception {
        procesador.procesar(mensaje());
        procesador.procesar(json.writerWithDefaultPrettyPrinter().writeValueAsBytes(json.readTree(mensaje())));
        verify(pdf,times(1)).generarSiFalta(any(),any());
    }
    @Test void mismoIdConContenidoDistintoSeRechazaSinReintentar() throws Exception {
        procesador.procesar(mensaje());
        assertThrows(ErrorPermanente.class, () -> procesador.procesar(json.writeValueAsBytes(sobre(orden("Otro contenido",List.of())))));
        verifyNoInteractions(espera);
    }
    @Test void recuperaPdfSiFallaRegistroDeExitoYReinicia() throws Exception {
        doThrow(new IOException("fallo temporal")).when(registro).completar(id);
        assertThrows(IOException.class, () -> procesador.procesar(mensaje()));
        byte[] bytes = Files.readAllBytes(registro.documento(id));
        var fecha = Files.getLastModifiedTime(registro.documento(id));
        assertFalse(Files.exists(dir.resolve(id+".ok")));
        registro.close(); registro = new RegistroPersistente(dir.toString()); procesador = nuevo();
        procesador.procesar(mensaje());
        assertArrayEquals(bytes,Files.readAllBytes(registro.documento(id)));
        assertEquals(fecha,Files.getLastModifiedTime(registro.documento(id)));
        assertTrue(Files.exists(dir.resolve(id+".ok")));
    }
    @Test void reintentosExactosYNackAlAgotarse() throws Exception {
        doThrow(new IOException("fallo temporal")).when(registro).completado(any(),any());
        Channel canal = mock(Channel.class);
        recibir(mensaje(),canal);
        verify(registro,times(3)).completado(any(),any());
        var orden = inOrder(espera);
        orden.verify(espera).pausar(2000); orden.verify(espera).pausar(5000);
        verify(canal).basicNack(7,false,false); verify(canal,never()).basicAck(anyLong(),anyBoolean());
    }
    @Test void falloTemporalSeRecuperaEnSegundoIntento() throws Exception {
        doThrow(new IOException("fallo temporal")).doCallRealMethod().when(registro).completado(any(),any());
        Channel canal = mock(Channel.class);
        recibir(mensaje(),canal);
        verify(espera).pausar(2000); verify(espera,never()).pausar(5000);
        verify(canal).basicAck(7,false); verify(canal,never()).basicNack(anyLong(),anyBoolean(),anyBoolean());
    }
    @Test void ackOcurreDespuesDePdfYRegistroDeExito() throws Exception {
        Channel canal = mock(Channel.class);
        doAnswer(inv -> { assertTrue(Files.exists(dir.resolve(id+".ok"))); return null; }).when(canal).basicAck(7,false);
        recibir(mensaje(),canal);
        var orden = inOrder(pdf,registro,canal);
        orden.verify(pdf).generarSiFalta(any(),any()); orden.verify(registro).completar(id); orden.verify(canal).basicAck(7,false);
    }
    @Test void falloDeAckNoGeneraNackYReentregaDeduplica() throws Exception {
        Channel canal = mock(Channel.class); doThrow(new IOException("canal cerrado")).when(canal).basicAck(7,false);
        assertThrows(IOException.class, () -> recibir(mensaje(),canal));
        verify(canal,never()).basicNack(anyLong(),anyBoolean(),anyBoolean());
        recibir(mensaje(),mock(Channel.class)); verify(pdf,times(1)).generarSiFalta(any(),any());
    }
    @Test void jsonInvalidoRechazadoSinEfectosNiReintentos() throws Exception {
        Channel canal = mock(Channel.class); recibir("{invalido".getBytes(),canal);
        verify(canal).basicNack(7,false,false); verifyNoInteractions(pdf,espera);
    }
    @Test void validaEstadoTipoVersionCorrelacionFechasYRepuestos() {
        var base = new String(mensaje(),java.nio.charset.StandardCharsets.UTF_8);
        List<String> invalidos = List.of(base.replace("CERRADA","ASIGNADA"), base.replace("GENERAR_REPORTE","ORDEN_CERRADA"),
                base.replace("1.0","2.0"), base.replace("\"correlationId\":\"15\"","\"correlationId\":\"16\""),
                base.replace("2026-10-15T12:00:00Z","2026-10-19T12:00:00Z"),
                base.replace("\"cantidad\":2","\"cantidad\":0"), base.replace("\"ordenId\":15","\"ordenId\":0"),
                base.replace("\"cantidad\":2","\"cantidad\":2.5"),
                base.replace("\"repuestoId\":4","\"repuestoId\":-1"), base.replace("tecnico-01",""),
                base.replace("\"repuestos\":[{\"repuestoId\":4,\"cantidad\":2}]","\"repuestos\":[null]"),
                base.substring(0,base.length()-1)+",\"jwt\":\"no-permitido\"}");
        for (String invalido : invalidos) assertThrows(ErrorPermanente.class, () -> procesador.procesar(invalido.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        verifyNoInteractions(pdf,espera);
    }
    @Test void bloqueaSegundaInstanciaSobreElMismoVolumen() {
        assertThrows(java.nio.channels.OverlappingFileLockException.class, () -> new RegistroPersistente(dir.toString()));
    }
    @Test void documentoCompletadoAlteradoNoSeConfirma() throws Exception {
        procesador.procesar(mensaje()); Files.writeString(registro.documento(id),"alterado");
        assertThrows(ErrorPermanente.class, () -> procesador.procesar(mensaje()));
    }
    @Test void limitaTamanoDelMensaje() {
        assertThrows(ErrorPermanente.class, () -> procesador.procesar(new byte[262145]));
        verifyNoInteractions(pdf,espera);
    }
}
