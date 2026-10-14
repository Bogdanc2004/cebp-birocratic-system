package ro.cebp.bureaucracy.common;

import org.junit.jupiter.api.Test;
import ro.cebp.bureaucracy.common.model.BureaucracyConfig;
import ro.cebp.bureaucracy.common.model.DocumentConfig;
import ro.cebp.bureaucracy.common.model.OfficeConfig;
import ro.cebp.bureaucracy.common.model.Range;
import ro.cebp.bureaucracy.common.model.ResourceConfig;
import ro.cebp.bureaucracy.common.model.SimulationConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    private static final SimulationConfig SIM =
            new SimulationConfig(10, new Range(1, 2), new Range(1, 2), 0.1, 5, 1L);

    private static void assertInvalid(BureaucracyConfig config, String expectedInMessage) {
        ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.validate(config));
        assertTrue(e.getMessage().contains(expectedInMessage), e.getMessage());
    }

    @Test
    void defaultConfigLoadsFromClasspath() {
        Bureaucracy b = ConfigLoader.load("config/default.json");
        assertEquals(5, b.offices().size());
        assertEquals("Notariat", b.documents().get("ContractVanzare").issuingOffice());
        assertEquals(200, b.simulation().clients());
        assertEquals(new Range(20, 80), b.simulation().serviceTimeMs());
    }

    @Test
    void shippedConfigsAreValid() {
        ConfigLoader.load("config/small.json");
        ConfigLoader.load("config/benchmark.json");
        ConfigLoader.load("config/races.json");
        ConfigLoader.load("config/shared-resources.json");
    }

    @Test
    void cyclicConfigIsRejected() {
        ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.load("config/cyclic.json"));
        assertTrue(e.getMessage().contains("cycle"), e.getMessage());
    }

    @Test
    void documentWithoutOfficeIsRejected() {
        ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.load("config/missing-office.json"));
        assertTrue(e.getMessage().contains("No office issues document 'B'"), e.getMessage());
    }

    @Test
    void missingFileIsReported() {
        assertThrows(ConfigException.class, () -> ConfigLoader.load("config/does-not-exist.json"));
    }

    @Test
    void duplicateOfficeIsRejected() {
        assertInvalid(new BureaucracyConfig(
                List.of(new OfficeConfig("P", 1, List.of("A")), new OfficeConfig("P", 1, List.of())),
                List.of(new DocumentConfig("A", null)), SIM), "Duplicate office");
    }

    @Test
    void officeWithoutCountersIsRejected() {
        assertInvalid(new BureaucracyConfig(
                List.of(new OfficeConfig("P", 0, List.of("A"))),
                List.of(new DocumentConfig("A", null)), SIM), "at least one counter");
    }

    @Test
    void documentIssuedByTwoOfficesIsRejected() {
        assertInvalid(new BureaucracyConfig(
                List.of(new OfficeConfig("P", 1, List.of("A")), new OfficeConfig("Q", 1, List.of("A"))),
                List.of(new DocumentConfig("A", null)), SIM), "issued by two offices");
    }

    @Test
    void officeIssuingUndeclaredDocumentIsRejected() {
        assertInvalid(new BureaucracyConfig(
                List.of(new OfficeConfig("P", 1, List.of("A", "Ghost"))),
                List.of(new DocumentConfig("A", null)), SIM), "undeclared document 'Ghost'");
    }

    @Test
    void sharedResourcesConfigLoads() {
        Bureaucracy b = ConfigLoader.load("config/shared-resources.json");
        assertEquals(List.of(new ResourceConfig("Registru", 1), new ResourceConfig("Stampila", 1)), b.resources());
        // The two offices list the resources in opposite orders on purpose (deadlock/livelock setting).
        assertEquals(List.of("Registru", "Stampila"), b.documents().get("AdeverintaDomiciliu").uses());
        assertEquals(List.of("Stampila", "Registru"), b.documents().get("CertificatFiscal").uses());
    }

    @Test
    void configWithoutResourcesHasNone() {
        Bureaucracy b = ConfigLoader.load("config/default.json");
        assertTrue(b.resources().isEmpty());
        assertTrue(b.documents().values().stream().allMatch(d -> d.uses().isEmpty()));
    }

    @Test
    void invalidResourcesAreRejected() {
        List<OfficeConfig> offices = List.of(new OfficeConfig("P", 1, List.of("A")));
        ResourceConfig stamp = new ResourceConfig("Stampila", 1);

        assertInvalid(new BureaucracyConfig(offices,
                List.of(new DocumentConfig("A", null, List.of("Ghost"))), List.of(stamp), SIM), "unknown resource 'Ghost'");
        assertInvalid(new BureaucracyConfig(offices,
                List.of(new DocumentConfig("A", null, List.of("Stampila", "Stampila"))), List.of(stamp), SIM), "twice");
        assertInvalid(new BureaucracyConfig(offices,
                List.of(new DocumentConfig("A", null)), List.of(stamp, stamp), SIM), "Duplicate resource");
        assertInvalid(new BureaucracyConfig(offices,
                List.of(new DocumentConfig("A", null)), List.of(new ResourceConfig("Stampila", 0)), SIM), "at least one permit");
    }

    @Test
    void invalidSimulationParametersAreRejected() {
        List<OfficeConfig> offices = List.of(new OfficeConfig("P", 1, List.of("A")));
        List<DocumentConfig> documents = List.of(new DocumentConfig("A", null));

        assertInvalid(new BureaucracyConfig(offices, documents, null), "simulation");
        assertInvalid(new BureaucracyConfig(offices, documents,
                new SimulationConfig(0, new Range(1, 2), new Range(1, 2), 0.1, 5, null)), "clients");
        assertInvalid(new BureaucracyConfig(offices, documents,
                new SimulationConfig(1, new Range(5, 2), new Range(1, 2), 0.1, 5, null)), "serviceTimeMs");
        assertInvalid(new BureaucracyConfig(offices, documents,
                new SimulationConfig(1, new Range(1, 2), new Range(1, 2), 1.5, 5, null)), "coffeeBreakChance");
    }
}
