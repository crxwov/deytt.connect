package space.deytt.connect

import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SubscriptionStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun failedWritePreservesLiveFileAndRemovesTemporaryFile() {
        val directory = temporary.newFolder()
        val target = File(directory, "current.json").apply { writeText("last good") }
        try {
            AtomicSubscriptionFile.write(target) {
                it.write("partial".toByteArray())
                throw IOException("simulated disk full")
            }
            fail("Expected write failure")
        } catch (_: IOException) { }
        assertEquals("last good", target.readText())
        assertEquals(listOf("current.json"), directory.list()!!.toList())
    }

    @Test fun failedRenameDoesNotCopyOverDestination() {
        val target = temporary.newFolder("current.json")
        File(target, "existing").writeText("keep")
        try {
            AtomicSubscriptionFile.write(target, "replacement")
            fail("Expected rename failure")
        } catch (_: IOException) { }
        assertEquals("keep", File(target, "existing").readText())
    }

    @Test fun storesPreservePreviousAndRejectEmptyReplacement() {
        val directory = temporary.newFolder()
        val store = SubscriptionStore(directory)
        store.saveValidated("first")
        store.saveValidated("second")
        assertEquals("first", store.readPrevious())
        assertEquals("second", store.readCurrent())
        try { store.saveValidated("  "); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
        assertEquals("second", store.readCurrent())
    }

    @Test fun concurrentInstancesNeverPublishPartialContents() {
        val directory = temporary.newFolder()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val contents = (0..20).map { "profile-$it:" + "x".repeat(8192) }.toSet()
            val futures = executor.invokeAll(contents.map { content -> Callable {
                val store = SubscriptionStore(directory)
                store.saveValidated(content)
                assertTrue(store.readCurrent() in contents)
            } })
            futures.forEach { it.get() }
            assertTrue(SubscriptionStore(directory).readPrevious() in contents)
        } finally { executor.shutdownNow() }
    }

    @Test fun malformedOptionalIndexDoesNotWriteOrThrow() {
        val directory = temporary.newFolder()
        val index = File(directory, "awg-profiles.json").apply { writeText("not json") }
        assertTrue(AwgProfileStore(directory).profiles().isEmpty())
        assertEquals("not json", index.readText())
    }

    @Test fun untrustedIndexPathsAndBadRecordsAreIgnoredWithoutMutatingCache() {
        val directory = temporary.newFolder()
        val source = JSONArray().put(JSONObject().put("version", "31").put("file", "../outside.conf"))
            .put(JSONObject().put("version", "31").put("file", "/outside.conf"))
            .put("malformed")
        val index = File(directory, "awg-profiles.json").apply { writeText(source.toString()) }
        assertTrue(AwgProfileStore(directory).profiles().isEmpty())
        assertEquals(source.toString(), index.readText())
    }

    @Test fun failedCoreCommitRestoresAwgIndexAndKeepsPriorFiles() {
        val directory = temporary.newFolder()
        val index = File(directory, "awg-profiles.json").apply { writeText("[]") }
        val old = File(directory, "awg-old.conf").apply { writeText("old") }
        try {
            AwgProfileStore(directory).saveWithCommit(emptyList()) { throw IOException("core commit failed") }
            fail("Expected failure")
        } catch (_: IOException) { }
        assertEquals("[]", index.readText())
        assertTrue(old.exists())
    }

    @Test fun recoveryRestoresAllFilesAfterEveryInterruptedPublishStage() {
        for (stage in 0..3) {
            val directory = temporary.newFolder()
            File(directory, "current.json").writeText("old-current")
            File(directory, "previous.json").writeText("old-previous")
            File(directory, "awg-profiles.json").writeText("[]")
            File(directory, "awg-old.conf").writeText("old-awg")
            try {
                AtomicSubscriptionFile.transaction(directory) {
                    if (stage >= 1) AtomicSubscriptionFile.write(File(directory, "awg-profiles.json"), "[{\"version\":\"new\"}]")
                    if (stage >= 2) AtomicSubscriptionFile.write(File(directory, "previous.json"), "old-current")
                    if (stage >= 3) AtomicSubscriptionFile.write(File(directory, "current.json"), "new-current")
                    // Fatal termination deliberately skips ordinary exception rollback.
                    throw SimulatedProcessDeath()
                }
            } catch (_: SimulatedProcessDeath) { }
            assertTrue(File(directory, ".subscription-transaction.json").exists())
            if (stage % 2 == 0) AwgProfileStore(directory).profiles()
            else SubscriptionStore(directory).readCurrent()
            assertEquals("old-current", SubscriptionStore(directory).readCurrent())
            assertEquals("old-previous", SubscriptionStore(directory).readPrevious())
            assertEquals("[]", File(directory, "awg-profiles.json").readText())
            assertTrue(File(directory, "awg-old.conf").exists())
            assertFalse(File(directory, ".subscription-transaction.json").exists())
        }
    }

    @Test fun interruptedFirstImportRestoresAbsenceOfProfiles() {
        val directory = temporary.newFolder()
        try {
            AwgProfileStore(directory).saveWithCommit(emptyList()) {
                SubscriptionStore(directory).saveValidated("new-current")
                throw SimulatedProcessDeath()
            }
        } catch (_: SimulatedProcessDeath) { }
        assertNull(SubscriptionStore(directory).readCurrent())
        assertNull(SubscriptionStore(directory).readPrevious())
        assertFalse(File(directory, "awg-profiles.json").exists())
    }

    @Test fun failedRecoveryRetainsJournalAndCanBeRetried() {
        val directory = temporary.newFolder()
        File(directory, "current.json").writeText("old-current")
        File(directory, "previous.json").writeText("old-previous")
        try {
            AtomicSubscriptionFile.transaction(directory) {
                AtomicSubscriptionFile.write(File(directory, "current.json"), "new-current")
                throw SimulatedProcessDeath()
            }
        } catch (_: SimulatedProcessDeath) { }
        val previous = File(directory, "previous.json")
        assertTrue(previous.delete())
        assertTrue(previous.mkdir())
        File(previous, "obstacle").writeText("block rename")
        try { AtomicSubscriptionFile.recover(directory); fail("Expected recovery failure") } catch (_: IOException) { }
        assertTrue(File(directory, ".subscription-transaction.json").exists())
        previous.deleteRecursively()
        assertEquals("old-current", SubscriptionStore(directory).readCurrent())
        assertEquals("old-previous", SubscriptionStore(directory).readPrevious())
    }

    @Test fun completedTransactionDoesNotRollbackOnNextRead() {
        val directory = temporary.newFolder()
        SubscriptionStore(directory).saveValidated("old-current")
        SubscriptionStore(directory).commitValidated("new-current", AwgProfileStore(directory), emptyList())
        assertEquals("new-current", SubscriptionStore(directory).readCurrent())
        assertEquals("old-current", SubscriptionStore(directory).readPrevious())
        assertEquals("[]", File(directory, "awg-profiles.json").readText())
        assertFalse(File(directory, ".subscription-transaction.json").exists())
    }

    @Test fun corruptJournalHidesProfilesWithoutDiscardingEvidence() {
        val directory = temporary.newFolder()
        val current = File(directory, "current.json").apply { writeText("uncertain-current") }
        val previous = File(directory, "previous.json").apply { writeText("uncertain-previous") }
        val index = File(directory, "awg-profiles.json").apply { writeText("[]") }
        val journal = File(directory, ".subscription-transaction.json").apply { writeText("damaged journal") }
        assertNull(SubscriptionStore(directory).readCurrent())
        assertNull(SubscriptionStore(directory).readPrevious())
        assertTrue(AwgProfileStore(directory).profiles().isEmpty())
        assertEquals("uncertain-current", current.readText())
        assertEquals("uncertain-previous", previous.readText())
        assertEquals("[]", index.readText())
        assertEquals("damaged journal", journal.readText())
        try { SubscriptionStore(directory).saveValidated("replacement"); fail("Expected write failure") } catch (_: IOException) { }
        assertEquals("uncertain-current", current.readText())
        assertEquals("damaged journal", journal.readText())
    }

    private class SimulatedProcessDeath : Error()

    @Test fun trafficCountersCannotOverflow() {
        assertEquals(Long.MAX_VALUE, SubscriptionMetadata(uploadBytes = Long.MAX_VALUE, downloadBytes = 1).usedBytes)
    }
}
