package io.github.srikarmk.hearth;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Names a phone sends with an upload, cleaned to what the launcher's wallpaper collection accepts. */
public class CompanionServerTest {

    @Test
    public void phoneNamesBecomeSafeFileNames() {
        assertEquals("img_2041.jpg", CompanionServer.clean("IMG 2041.JPG"));
        assertEquals("spider-man_noir.png", CompanionServer.clean("Spider-Man Noir.png"));
        assertEquals("a_b.webp", CompanionServer.clean("../a b.webp"));
    }

    @Test
    public void onlyPicturesTheLauncherReads() {
        assertNull(CompanionServer.clean("IMG_2041.HEIC"));
        assertNull(CompanionServer.clean("notes.txt"));
        assertNull(CompanionServer.clean("noextension"));
        assertNull(CompanionServer.clean(null));
    }

    @Test
    public void anEmptyNameGetsOne() {
        assertTrue(CompanionServer.clean("???.jpg").matches("phone_\\d+\\.jpg"));
    }
}
