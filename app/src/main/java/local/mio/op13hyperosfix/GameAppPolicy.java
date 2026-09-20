package local.mio.op13hyperosfix;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class GameAppPolicy {
    static final int INHERIT = -1;

    private final int mode;
    private final int fas;

    GameAppPolicy(int mode, int fas) {
        this.mode = sanitizeMode(mode);
        this.fas = sanitizeFas(fas);
    }

    int getMode() {
        return mode;
    }

    int getFas() {
        return fas;
    }

    GameAppPolicy withMode(int value) {
        return new GameAppPolicy(value, fas);
    }

    GameAppPolicy withFas(int value) {
        return new GameAppPolicy(mode, value);
    }

    boolean isInherited() {
        return mode == INHERIT && fas == INHERIT;
    }

    static GameAppPolicy inherited() {
        return new GameAppPolicy(INHERIT, INHERIT);
    }

    static Map<String, GameAppPolicy> parse(String raw) {
        Map<String, GameAppPolicy> result = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return result;
        for (String record : raw.split(",")) {
            int fasSplit = record.lastIndexOf(".f");
            int modeSplit = fasSplit < 0 ? -1 : record.lastIndexOf(".m", fasSplit - 1);
            if (modeSplit <= 0 || fasSplit <= modeSplit + 2) continue;
            String packageName = record.substring(0, modeSplit);
            if (!isPackageName(packageName)) continue;
            try {
                int mode = Integer.parseInt(record.substring(modeSplit + 2, fasSplit));
                int fas = Integer.parseInt(record.substring(fasSplit + 2));
                GameAppPolicy policy = new GameAppPolicy(mode, fas);
                if (!policy.isInherited()) result.put(packageName, policy);
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }

    static String encode(Map<String, GameAppPolicy> policies) {
        if (policies == null || policies.isEmpty()) return "";
        List<String> packages = new ArrayList<>(policies.keySet());
        Collections.sort(packages);
        StringBuilder output = new StringBuilder();
        for (String packageName : packages) {
            GameAppPolicy policy = policies.get(packageName);
            if (!isPackageName(packageName) || policy == null || policy.isInherited()) continue;
            if (output.length() > 0) output.append(',');
            output.append(packageName)
                    .append(".m").append(policy.mode)
                    .append(".f").append(policy.fas);
        }
        return output.toString();
    }

    static boolean isPackageName(String value) {
        return value != null && value.matches("[A-Za-z0-9._]+");
    }

    private static int sanitizeMode(int value) {
        return value >= 0 && value <= 3 ? value : INHERIT;
    }

    private static int sanitizeFas(int value) {
        return value == 0 || value == 1 ? value : INHERIT;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof GameAppPolicy)) return false;
        GameAppPolicy other = (GameAppPolicy) value;
        return mode == other.mode && fas == other.fas;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, fas);
    }
}
