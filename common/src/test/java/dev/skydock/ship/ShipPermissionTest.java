package dev.skydock.ship;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class ShipPermissionTest {
    private static final Function<String, String> TEAMS = Map.of("Ava", "Raiders", "Bo", "Traders")::get;

    @Test void theOwnersCurrentTeamShares() {
        assertEquals("Raiders", ShipManager.sharedTeam(true, "Ava", TEAMS, "Raiders"));
        assertEquals("Traders", ShipManager.sharedTeam(true, "Bo", TEAMS, "Raiders"), "the team recorded at claim time is ignored once the owner moved");
    }
    @Test void anOwnerWithNoTeamOrNoKnownNameSharesWithNoOne() {
        assertEquals("", ShipManager.sharedTeam(true, "Cy", TEAMS, "Raiders"), "the owner left every team");
        assertEquals("", ShipManager.sharedTeam(true, null, TEAMS, "Raiders"), "the owner is not in the profile cache");
    }
    @Test void theStoredTeamIsOnlyAFallbackWhenNoLookupIsPossible() {
        assertEquals("Raiders", ShipManager.sharedTeam(false, null, TEAMS, "Raiders"));
        assertEquals("", ShipManager.sharedTeam(false, null, TEAMS, ""));
        assertEquals("", ShipManager.sharedTeam(false, null, TEAMS, null));
    }
}
