package com.termux.app;
import org.junit.Test;
import static org.junit.Assert.*;
public class PhonePolicyTest {
    @Test public void exactContactNamesHandleCaseUnicodeAndWhitespace() {
        assertEquals(PhonePolicy.name(" AKHILESH  Kumar "),PhonePolicy.name("Akhilesh Kumar"));
        assertEquals(PhonePolicy.name("Ａkhilesh"),PhonePolicy.name("Akhilesh"));
        assertNotEquals(PhonePolicy.name("Akhilesh"),PhonePolicy.name("Akhilesh Office"));
    }
    @Test public void securityScreensRemainOutsideAutomation() {
        assertTrue(PhonePolicy.protectedPackage("com.google.android.permissioncontroller"));
        assertTrue(PhonePolicy.protectedPackage("com.android.systemui"));
        assertTrue(PhonePolicy.protectedPackage("com.android.settings"));
        assertFalse(PhonePolicy.protectedPackage("com.whatsapp"));
        assertFalse(PhonePolicy.whatsapp("com.whatsapp.fake"));
    }
    @Test public void duplicateSendIdentityIncludesUserTurnRecipientAndExactText() {
        String key=PhonePolicy.fingerprint("session:user1","+919999999999","Hi");
        assertEquals(key,PhonePolicy.fingerprint("session:user1","+919999999999","Hi"));
        assertNotEquals(key,PhonePolicy.fingerprint("session:user2","+919999999999","Hi"));
        assertNotEquals(key,PhonePolicy.fingerprint("session:user1","+918888888888","Hi"));
        assertNotEquals(key,PhonePolicy.fingerprint("session:user1","+919999999999","Hi!"));
    }
}
