package net.cumba.corej.rest.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for {@link FindingRow} — the EC-40 record-key fields and their serialization.
 *
 * <p>
 * The key fields are <b>null, not empty</b>, when nothing resolved, and are omitted from the JSON.
 * That is what keeps a run executed under the default {@code corej.findingKeys=off} producing a
 * payload identical to a pre-EC-40 build, and what keeps "no key" distinguishable from "empty key".
 * </p>
 */
class FindingRowTest
{

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static FindingRow row(List<String> aKeyVariables, Map<String, String> aKeys,
            String aKeySource)
    {
        return new FindingRow("CORE-1", "dm.xpt", "S1", 3, "2", "executable", "m", List.of("AGE"),
                List.of("12"), "DM", aKeyVariables, aKeys, aKeySource);
    }


    @Test
    void aRowWithoutARecordKeyOmitsAllThreeFieldsEntirely()
    {
        assertThat(MAPPER.writeValueAsString(row(null, null, null)))
                .isEqualTo("{\"coreId\":\"CORE-1\",\"dataset\":\"dm.xpt\",\"usubjid\":\"S1\","
                        + "\"row\":3,\"seq\":\"2\",\"executability\":\"executable\","
                        + "\"message\":\"m\",\"variables\":[\"AGE\"],\"values\":[\"12\"],"
                        + "\"domain\":\"DM\"}");
    }


    @Test
    void aRowWithARecordKeyCarriesTheSchemaTheValuesAndTheTier()
    {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("QNAM", "AESOSP");
        keys.put("IDVARVAL", "3");

        assertThat(MAPPER.writeValueAsString(row(List.of("QNAM", "IDVARVAL"), keys, "DEFINE_KEY")))
                .endsWith("\"domain\":\"DM\",\"keyVariables\":[\"QNAM\",\"IDVARVAL\"],"
                        + "\"keys\":{\"QNAM\":\"AESOSP\",\"IDVARVAL\":\"3\"},"
                        + "\"keySource\":\"DEFINE_KEY\"}");
    }


    @Test
    void aKeyColumnTheRowDoesNotCarrySerializesAsNullRatherThanBeingDropped()
    {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("QNAM", "AESOSP");
        keys.put("IDVARVAL", null);

        assertThat(MAPPER.writeValueAsString(row(List.of("QNAM", "IDVARVAL"), keys, "STRUCTURAL")))
                .contains("\"keys\":{\"QNAM\":\"AESOSP\",\"IDVARVAL\":null}");
    }


    @Test
    void theKeyCollectionsAreDefensivelyCopiedAndUnmodifiable()
    {
        List<String> names = new ArrayList<>(List.of("QNAM"));
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("QNAM", "AESOSP");
        FindingRow r = row(names, keys, "NATURAL");

        names.add("IDVAR");
        keys.put("IDVAR", "AESEQ");
        assertThat(r.keyVariables()).containsExactly("QNAM");
        assertThat(r.keys()).containsExactlyEntriesOf(Map.of("QNAM", "AESOSP"));
        assertThatThrownBy(() -> r.keys().put("X", "Y"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> r.keyVariables().add("X"))
                .isInstanceOf(UnsupportedOperationException.class);
    }


    @Test
    void nullVariablesAndValuesStillBecomeEmptyLists()
    {
        FindingRow r = new FindingRow(null, null, null, null, null, null, null, null, null, null,
                null, null, null);
        assertThat(r.variables()).isEmpty();
        assertThat(r.values()).isEmpty();
        assertThat(r.keyVariables()).isNull();
        assertThat(r.keys()).isNull();
    }
}
