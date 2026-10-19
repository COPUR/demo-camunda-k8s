package com.bank.bpo.workerkit;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/**
 * The only way a worker hands variables back to the engine (05 section 5.1): the names must be the ones
 * the job type declares, must not look like money or personal data, each value is an id or an enum (a plain
 * token, see {@link Ids}) or a boolean, and the JSON the engine receives stays under 8 KB.
 */
public final class OutputVariables {

    public static final int MAX_BYTES = 8 * 1024;

    // Same list as lint/plugin/lib/dictionary.js FORBIDDEN_NAME; OutputVariablesTest keeps them in step.
    static final Pattern FORBIDDEN = Pattern.compile(
        "(amount|balance|iban|fee|rate|price|salary|income|firstname|lastname|fullname|^name$|email|e-mail|phone|msisdn"
            + "|tckn|emiratesid|nationalid|passport|address|birth)", Pattern.CASE_INSENSITIVE);

    private static final Pattern VALUE = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private OutputVariables() {
    }

    public static Map<String, Object> of(Set<String> declared, Map<String, ?> values) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, ?> e : values.entrySet()) {
            if (FORBIDDEN.matcher(e.getKey()).find()) {
                throw new IllegalStateException("output variable looks like money or personal data: " + e.getKey());
            }
            if (!declared.contains(e.getKey())) {
                throw new IllegalStateException("output variable is not declared by the job type: " + e.getKey());
            }
            Object value = e.getValue();
            if (!(value instanceof Boolean) && !(value instanceof String s && VALUE.matcher(s).matches())) {
                throw new IllegalStateException("output variable is not an id, an enum or a boolean: " + e.getKey());
            }
            out.put(e.getKey(), value);
        }
        if (JSON.writeValueAsString(out).getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalStateException("output variables exceed " + MAX_BYTES + " bytes");
        }
        return out;
    }
}
