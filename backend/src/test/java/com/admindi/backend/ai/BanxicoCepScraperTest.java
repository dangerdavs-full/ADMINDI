package com.admindi.backend.ai;

import com.admindi.backend.repository.BanxicoScrapeSchemaRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class BanxicoCepScraperTest {

    private final BanxicoCepScraper scraper =
            new BanxicoCepScraper(mock(BanxicoScrapeSchemaRepository.class));

    @Test
    void parseCepXml_extractsCriticalFieldsFromOfficialXml() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <SPEI_Tercero FechaOperacion="2025-05-06" Hora="13:26:14" ClaveSPEI="40030"
                              claveRastreo="10HZA71753">
                    <Beneficiario BancoReceptor="BAJIO" Nombre="DURAN GONZALEZ FRANCISCO EDMUNDO"
                                   TipoCuenta="40" Cuenta="030320900036588113" RFC="DUGF781222MS0"
                                   Concepto="TRASPASO" IVA="0.00" MontoPago="23000.00"/>
                    <Ordenante BancoEmisor="STP" Nombre="ZAC BANQUETES Y ALIMENTOS SA DE CV"
                               TipoCuenta="40" Cuenta="646180546800000006" RFC="ZBA180130497"/>
                </SPEI_Tercero>
                """;

        Map<String, String> fields = scraper.parseCepXml(xml);

        assertEquals("10HZA71753", fields.get("claveRastreo"));
        assertEquals("2025-05-06", fields.get("fechaOperacion"));
        assertEquals("23000.00", fields.get("monto"));
        assertEquals("STP", fields.get("bancoEmisor"));
        assertEquals("BAJIO", fields.get("bancoReceptor"));
        assertEquals("030320900036588113", fields.get("cuentaBeneficiario"));
    }

    @Test
    void resolveInstitutionCode_acceptsOfficialCodeDisplayNameAndAlias() {
        List<BanxicoCepScraper.InstitutionEntry> institutions = List.of(
                new BanxicoCepScraper.InstitutionEntry("40012", "BBVA MEXICO", "BBVA MEXICO"),
                new BanxicoCepScraper.InstitutionEntry("40014", "SANTANDER", "SANTANDER"),
                new BanxicoCepScraper.InstitutionEntry("90646", "STP", "STP")
        );

        assertEquals("40012", scraper.resolveInstitutionCode("40012", institutions));
        assertEquals("40012", scraper.resolveInstitutionCode("BBVA", institutions));
        assertEquals("40012", scraper.resolveInstitutionCode("bbva mexico", institutions));
        assertEquals("90646", scraper.resolveInstitutionCode("STP", institutions));
    }

    @Test
    void resolveReceiverCode_usesClabePrefixAgainstBanxicoInstitutionCode() {
        List<BanxicoCepScraper.InstitutionEntry> institutions = List.of(
                new BanxicoCepScraper.InstitutionEntry("40012", "BBVA MEXICO", "BBVA MEXICO"),
                new BanxicoCepScraper.InstitutionEntry("40072", "BANORTE", "BANORTE"),
                new BanxicoCepScraper.InstitutionEntry("90646", "STP", "STP")
        );

        assertEquals("40012", scraper.resolveReceiverCode("012180015012345678", institutions));
        assertEquals("40072", scraper.resolveReceiverCode("072180015012345678", institutions));
        assertEquals("90646", scraper.resolveReceiverCode("646180546800000006", institutions));
    }
}
