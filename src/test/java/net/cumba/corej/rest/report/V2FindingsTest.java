package net.cumba.corej.rest.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for {@link V2Findings} — the narrow typed façade over the v2 {@code Findings} array.
 *
 * <p>
 * Most of these tests assert that a <b>malformed</b> document throws. That is the whole point of
 * the façade (plan decision D35): a lenient parser turns a silently-absent field into a
 * silently-empty column on the findings API, and nothing goes red. Every "missing" / "wrong type" /
 * "unrecognised" case below is a regression guard against re-introducing leniency — if one of them
 * ever starts passing with an empty result instead of an exception, the façade has been broken.
 * </p>
 */
class V2FindingsTest
{

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final String KEYED_LOCATION = """
            {"dataset":"SUPPAE","variables":["QVAL"],\
            "keyVariables":["RDOMAIN","IDVAR","IDVARVAL","QNAM"],"keySource":"STRUCTURAL"}""";

    private static final String PLAIN_LOCATION = "{\"dataset\":\"DM\",\"variables\":[\"AGE\"]}";

    private static final String PLAIN_ROW = "{\"row\":7,\"USUBJID\":\"S1\",\"SEQ\":\"2\","
            + "\"values\":[\"Y\"]}";

    private static final String KEYED_ROWS = """
            [{"row":7,"USUBJID":"S1","SEQ":"",\
            "keys":{"RDOMAIN":"AE","IDVAR":"AESEQ","IDVARVAL":"3","QNAM":"AESOSP"},\
            "values":["Y"]},\
            {"row":10,"USUBJID":"S2","SEQ":"",\
            "keys":{"RDOMAIN":"AE","IDVAR":"AESEQ","IDVARVAL":null,"QNAM":"AESOSP"},\
            "values":["N"]}]""";

    /** A one-finding v2 document built around the two supplied JSON fragments. */
    private static JsonNode doc(String aLocation, String aRows)
    {
        return json("""
                {"Report_Version":"2.0","Findings":[{"core_id":"C1","message":"m",\
                "executability":"e","dataset":"dm.xpt","domain":"DM","location":%s,\
                "variables":["AGE"],"rows":%s}]}""".formatted(aLocation, aRows));
    }


    private static JsonNode json(String aDocument)
    {
        return MAPPER.readTree(aDocument);
    }


    /** The expected SUPPAE record key in {@code keyVariables} order, with the given IDVARVAL. */
    private static Map<String, String> keyMap(String aIdVarVal)
    {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("RDOMAIN", "AE");
        expected.put("IDVAR", "AESEQ");
        expected.put("IDVARVAL", aIdVarVal);
        expected.put("QNAM", "AESOSP");
        return expected;
    }

    // ------------------------------------------------------------------
    // Happy path
    // ------------------------------------------------------------------


    @Test
    void parsesAKeyedFindingIntoTypedRecords()
    {
        V2Findings.Finding f = V2Findings.parse(doc(KEYED_LOCATION, KEYED_ROWS)).get(0);

        assertThat(f.coreId()).isEqualTo("C1");
        assertThat(f.message()).isEqualTo("m");
        assertThat(f.executability()).isEqualTo("e");
        assertThat(f.dataset()).isEqualTo("dm.xpt");
        assertThat(f.domain()).isEqualTo("DM");
        assertThat(f.variables()).containsExactly("AGE");
        assertThat(f.location().dataset()).isEqualTo("SUPPAE");
        assertThat(f.location().variables()).containsExactly("QVAL");
        assertThat(f.location().keyVariables()).containsExactly("RDOMAIN", "IDVAR", "IDVARVAL",
                "QNAM");
        assertThat(f.location().keySource()).isEqualTo("STRUCTURAL");

        assertThat(f.rows()).hasSize(2);
        V2Findings.Row first = f.rows().get(0);
        assertThat(first.row()).isEqualTo(7);
        assertThat(first.usubjid()).isEqualTo("S1");
        assertThat(first.seq()).isEmpty();
        assertThat(first.values()).containsExactly("Y");
        // The map preserves the document's key order, which is the location's keyVariables order.
        assertThat(first.keys()).containsExactlyEntriesOf(keyMap("3"));
        // A key column the row does not carry is a null value, not an absent entry — so the map
        // cannot be a Map.copyOf.
        assertThat(f.rows().get(1).keys()).containsExactlyEntriesOf(keyMap(null));
    }


    @Test
    void anUnkeyedFindingHasNullKeyFieldsThroughout()
    {
        V2Findings.Finding f = V2Findings.parse(doc(PLAIN_LOCATION, "[" + PLAIN_ROW + "]")).get(0);

        assertThat(f.location().keyVariables()).isNull();
        assertThat(f.location().keySource()).isNull();
        assertThat(f.rows().get(0).keys()).isNull();
    }


    @Test
    void aNullableTextFieldMayBeJsonNull()
    {
        JsonNode node = json("""
                {"Findings":[{"core_id":null,"message":null,"executability":null,"dataset":null,\
                "domain":null,"location":{"dataset":null,"variables":[]},"variables":[],\
                "rows":[]}]}""");
        V2Findings.Finding f = V2Findings.parse(node).get(0);

        assertThat(f.coreId()).isNull();
        assertThat(f.message()).isNull();
        assertThat(f.executability()).isNull();
        assertThat(f.dataset()).isNull();
        assertThat(f.domain()).isNull();
        assertThat(f.location().dataset()).isNull();
    }


    @Test
    void aNonRowScopedRowNumberIsTheEmptyStringAndBecomesNull()
    {
        // The writer emits "" rather than 0 for a finding that is not row-scoped; it must not be
        // coerced into a row number.
        V2Findings.Row row = V2Findings
                .parse(doc(PLAIN_LOCATION,
                        "[{\"row\":\"\",\"USUBJID\":\"\",\"SEQ\":\"\",\"values\":[\"null\"]}]"))
                .get(0).rows().get(0);
        assertThat(row.row()).isNull();
    }


    @Test
    void anEmptyFindingsArrayAndAZeroRowVirtualFindingParse()
    {
        assertThat(V2Findings.parse(json("{\"Findings\":[]}"))).isEmpty();

        List<V2Findings.Finding> virtual = V2Findings.parse(doc(PLAIN_LOCATION, "[]"));
        assertThat(virtual).hasSize(1);
        assertThat(virtual.get(0).rows()).isEmpty();
    }

    // ------------------------------------------------------------------
    // Strictness
    // ------------------------------------------------------------------


    @Test
    void rootMustBeAnObjectCarryingAFindingsArrayOfObjects()
    {
        assertThatThrownBy(() -> V2Findings.parse(json("[]")))
                .isInstanceOf(V2ReportFormatException.class)
                .hasMessageContaining("root is not a JSON object");
        assertThatThrownBy(() -> V2Findings.parse(json("{\"Report_Version\":\"2.0\"}")))
                .isInstanceOf(V2ReportFormatException.class)
                .hasMessageContaining("missing required field \"Findings\"");
        assertThatThrownBy(() -> V2Findings.parse(json("{\"Findings\":{}}")))
                .isInstanceOf(V2ReportFormatException.class)
                .hasMessageContaining("\"Findings\" is not an array");
        assertThatThrownBy(() -> V2Findings.parse(json("{\"Findings\":[1]}")))
                .isInstanceOf(V2ReportFormatException.class)
                .hasMessageContaining("Findings[0] is not a JSON object");
    }


    @Test
    void aMissingFindingFieldThrowsRatherThanYieldingAnEmptyColumn()
    {
        // Without this, "domain" would silently read as null and every domain-scoped findings
        // query would return nothing, with no error anywhere. That is the failure D35 removes.
        JsonNode node = json("""
                {"Findings":[{"core_id":"C1","message":"m","executability":"e",\
                "dataset":"dm.xpt","location":{"dataset":"DM","variables":[]},\
                "variables":[],"rows":[]}]}""");
        assertThatThrownBy(() -> V2Findings.parse(node)).isInstanceOf(V2ReportFormatException.class)
                .hasMessageContaining("Findings[0] is missing required field \"domain\"");
    }


    @Test
    void anUnrecognisedFindingFieldThrows()
    {
        JsonNode node = json("""
                {"Findings":[{"core_id":"C1","message":"m","executability":"e",\
                "dataset":"dm.xpt","domain":"DM","severity":"ERROR",\
                "location":{"dataset":"DM","variables":[]},"variables":[],"rows":[]}]}""");
        assertThatThrownBy(() -> V2Findings.parse(node)).isInstanceOf(V2ReportFormatException.class)
                .hasMessageContaining("Findings[0] carries unrecognised field \"severity\"");
    }


    @Test
    void aWronglyTypedFindingFieldThrows()
    {
        JsonNode badCoreId = json("""
                {"Findings":[{"core_id":1,"message":"m","executability":"e","dataset":"d",\
                "domain":"DM","location":{"dataset":"DM","variables":[]},"variables":[],\
                "rows":[]}]}""");
        assertThatThrownBy(() -> V2Findings.parse(badCoreId))
                .isInstanceOf(V2ReportFormatException.class)
                .hasMessageContaining("Findings[0].core_id is not a string");

        assertThatThrownBy(() -> V2Findings.parse(doc(PLAIN_LOCATION, "{}")))
                .isInstanceOf(V2ReportFormatException.class)
                .hasMessageContaining("Findings[0].rows is not an array");
    }


    @Test
    void aWronglyTypedStringArrayThrows()
    {
        assertThatThrownBy(
                () -> V2Findings.parse(doc("{\"dataset\":\"DM\",\"variables\":\"AGE\"}", "[]")))
                        .isInstanceOf(V2ReportFormatException.class)
                        .hasMessageContaining("Findings[0].location.variables is not an array");
        assertThatThrownBy(
                () -> V2Findings.parse(doc("{\"dataset\":\"DM\",\"variables\":[7]}", "[]")))
                        .isInstanceOf(V2ReportFormatException.class)
                        .hasMessageContaining("Findings[0].location.variables[0] is not a string");
    }


    @Test
    void aMalformedLocationThrows()
    {
        assertThatThrownBy(() -> V2Findings.parse(doc("[]", "[]")))
                .isInstanceOf(V2ReportFormatException.class)
                .hasMessageContaining("Findings[0].location is not a JSON object");
        assertThatThrownBy(() -> V2Findings.parse(doc("{\"variables\":[]}", "[]")))
                .isInstanceOf(V2ReportFormatException.class)
                .hasMessageContaining("Findings[0].location is missing required field \"dataset\"");
        assertThatThrownBy(() -> V2Findings
                .parse(doc("{\"dataset\":\"DM\",\"variables\":[],\"scope\":\"ROW\"}", "[]")))
                        .isInstanceOf(V2ReportFormatException.class)
                        .hasMessageContaining("carries unrecognised field \"scope\"");
    }


    @Test
    void keyVariablesAndKeySourceMustAppearTogether()
    {
        assertThatThrownBy(() -> V2Findings
                .parse(doc("{\"dataset\":\"DM\",\"variables\":[],\"keyVariables\":[\"X\"]}", "[]")))
                        .isInstanceOf(V2ReportFormatException.class)
                        .hasMessageContaining("only one of \"keyVariables\"/\"keySource\"");
        assertThatThrownBy(() -> V2Findings.parse(
                doc("{\"dataset\":\"DM\",\"variables\":[],\"keySource\":\"NATURAL\"}", "[]")))
                        .isInstanceOf(V2ReportFormatException.class)
                        .hasMessageContaining("only one of \"keyVariables\"/\"keySource\"");
    }


    @Test
    void aMalformedRowThrows()
    {
        assertThatThrownBy(() -> V2Findings.parse(doc(PLAIN_LOCATION, "[[]]")))
                .isInstanceOf(V2ReportFormatException.class)
                .hasMessageContaining("Findings[0].rows[0] is not a JSON object");
        assertThatThrownBy(() -> V2Findings
                .parse(doc(PLAIN_LOCATION, "[{\"row\":1,\"SEQ\":\"2\",\"values\":[]}]")))
                        .isInstanceOf(V2ReportFormatException.class).hasMessageContaining(
                                "Findings[0].rows[0] is missing required field \"USUBJID\"");
        assertThatThrownBy(() -> V2Findings.parse(doc(PLAIN_LOCATION,
                "[{\"row\":1,\"USUBJID\":\"S1\",\"SEQ\":\"2\",\"values\":[],\"note\":\"x\"}]")))
                        .isInstanceOf(V2ReportFormatException.class)
                        .hasMessageContaining("carries unrecognised field \"note\"");
        assertThatThrownBy(() -> V2Findings.parse(doc(PLAIN_LOCATION,
                "[{\"row\":\"3\",\"USUBJID\":\"S1\",\"SEQ\":\"2\",\"values\":[]}]")))
                        .isInstanceOf(V2ReportFormatException.class)
                        .hasMessageContaining("neither a number nor the empty string");
        assertThatThrownBy(() -> V2Findings.parse(doc(PLAIN_LOCATION,
                "[{\"row\":1,\"USUBJID\":\"S1\",\"SEQ\":\"2\",\"values\":{}}]")))
                        .isInstanceOf(V2ReportFormatException.class)
                        .hasMessageContaining("Findings[0].rows[0].values is not an array");
    }


    @Test
    void theRowKeyMustBePresentExactlyWhenTheLocationDeclaresKeyVariables()
    {
        // The writer emits "keys" on every row of a keyed finding and on no row of an unkeyed one.
        // Either half of that contract breaking silently would empty the SPA's Key column.
        assertThatThrownBy(() -> V2Findings.parse(doc(KEYED_LOCATION, "[" + PLAIN_ROW + "]")))
                .isInstanceOf(V2ReportFormatException.class)
                .hasMessageContaining("is missing \"keys\"");
        assertThatThrownBy(() -> V2Findings.parse(doc(PLAIN_LOCATION, KEYED_ROWS)))
                .isInstanceOf(V2ReportFormatException.class).hasMessageContaining(
                        "carries \"keys\" although its location declares no key variables");
    }


    @Test
    void aMalformedRowKeyThrows()
    {
        assertThatThrownBy(() -> V2Findings.parse(doc(KEYED_LOCATION,
                "[{\"row\":1,\"USUBJID\":\"S1\",\"SEQ\":\"\",\"keys\":[],\"values\":[]}]")))
                        .isInstanceOf(V2ReportFormatException.class)
                        .hasMessageContaining("Findings[0].rows[0].keys is not an object");
        assertThatThrownBy(() -> V2Findings.parse(doc(KEYED_LOCATION,
                "[{\"row\":1,\"USUBJID\":\"S1\",\"SEQ\":\"\",\"keys\":{\"RDOMAIN\":1},"
                        + "\"values\":[]}]"))).isInstanceOf(V2ReportFormatException.class)
                                .hasMessageContaining(
                                        "Findings[0].rows[0].keys.RDOMAIN is not a string");
    }
}
