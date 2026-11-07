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
    @Test public void draftReplacementSendsOnlyTheRequestedTextAndPreservesTheOriginal() {
        PhonePolicy.DraftReplacement plan=new PhonePolicy.DraftReplacement("Unfinished private draft", "Hi");
        assertEquals("Hi",plan.outgoing);
        assertEquals("Unfinished private draft",plan.backup);
        assertEquals("",new PhonePolicy.DraftReplacement("Hi","Hi").backup);
        String longDraft=new String(new char[3500]).replace('\0','x');
        assertEquals(longDraft,new PhonePolicy.DraftReplacement(longDraft,"Hi").backup);
    }
    @Test public void hintsAndLongComposerTextAreHandledWithoutTruncation() {
        assertEquals("",PhonePolicy.editorText("Message",true));
        assertEquals("Message",PhonePolicy.editorText("Message",false));
        String text=new String(new char[3500]).replace('\0','a');
        assertEquals(text,PhonePolicy.editorText(text,false));
        assertEquals(text,new PhonePolicy.DraftReplacement("",text).outgoing);
    }
    @Test public void sendConfirmationAcceptsHintsButRequiresANewMatchingMessage() {
        assertTrue(PhonePolicy.submissionVisible("Hi","",2,3));
        assertTrue(PhonePolicy.submissionVisible("Hi","Message",2,3));
        assertFalse(PhonePolicy.submissionVisible("Hi","Hi",2,3));
        assertFalse(PhonePolicy.submissionVisible("Hi","Message",2,2));
    }
    @Test public void focusRecoveryOnlyResumesTheSelectedAppFromJarvis() {
        assertTrue(PhonePolicy.shouldRestoreFocus("com.termux","com.example.bus","com.termux"));
        assertFalse(PhonePolicy.shouldRestoreFocus("com.other.app","com.example.bus","com.termux"));
        assertFalse(PhonePolicy.shouldRestoreFocus("com.termux",null,"com.termux"));
        assertFalse(PhonePolicy.shouldRestoreFocus("com.termux","com.termux","com.termux"));
        assertFalse(PhonePolicy.shouldRestoreFocus("com.termux","com.android.settings","com.termux"));
    }
}
