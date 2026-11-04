package dev.skydock.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShopPanelTest {
    @Test void scrollingStopsWhenTheLastRowsFill() {
        int[] rows = {13, 20, 20, 13, 20, 20};
        assertEquals(3, ShopPanel.maxScroll(rows, 60), "the last three rows (53) fit; a fourth would not");
        assertEquals(0, ShopPanel.maxScroll(rows, 200), "everything fits, so nothing scrolls");
        assertEquals(5, ShopPanel.maxScroll(rows, 10), "even a too-short panel can reach the last row");
        assertEquals(0, ShopPanel.maxScroll(new int[0], 60));
    }
}
