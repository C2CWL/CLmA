package com.clma.app.data.secrets

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clma.app.skills.js.SkillSecretsStore
import com.clma.app.workflow.secrets.WorkflowSecretsStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecretStoresEncryptionTest {
    private lateinit var context: Context
    private val preferences = mutableListOf<SharedPreferences>()
    private val value = "encryption-regression-test-value"

    @Before fun setUp() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                app.getSharedPreferences("test-secret-stores-$suffix-$name", mode).also {
                    if (it !in preferences) preferences.add(it)
                }
        }
    }

    @After fun tearDown() {
        preferences.forEach { it.edit().clear().commit() }
    }

    @Test fun agentWritesCiphertextAndReadsLegacyEntries() {
        val store = AgentSecretsStore(context)
        val prefs = context.getSharedPreferences(AgentSecretsStore.PREFS_NAME, Context.MODE_PRIVATE)
        seedLegacy(prefs, "ag_secret_probe", "ag_iv_probe", "plain")
        assertEquals(value, store.get("probe"))
        assertTrue(store.set("probe", value))
        assertEncrypted(prefs, "ag_secret_probe", "ag_iv_probe", "plain")
        assertEquals(value, AgentSecretsStore(context).get("probe"))
        assertFalse(store.set("invalid name", "replacement"))
        assertEquals(value, store.get("probe"))
    }

    @Test fun workflowWritesCiphertextAndReadsLegacyEntries() {
        val store = WorkflowSecretsStore(context)
        val prefs = context.getSharedPreferences(WorkflowSecretsStore.PREFS_NAME, Context.MODE_PRIVATE)
        seedLegacy(prefs, "wf_secret_probe", "wf_iv_probe", "plain")
        assertEquals(value, store.get("probe"))
        assertTrue(store.set("probe", value))
        assertEncrypted(prefs, "wf_secret_probe", "wf_iv_probe", "plain")
        assertEquals(value, WorkflowSecretsStore(context).get("probe"))
    }

    @Test fun skillWritesCiphertextAndReadsLegacyIvNamespace() {
        val store = SkillSecretsStore(context)
        val prefs = context.getSharedPreferences("skill_secrets", Context.MODE_PRIVATE)
        seedLegacy(prefs, "skill_secret_probe__token", "skill_secret_iv_probe__token", "__no_keystore__")
        assertEquals(value, store.get("probe", "token"))
        store.set("probe", "token", value)
        assertEncrypted(prefs, "skill_secret_probe__token", "skill_iv_probe__token", "__no_keystore__")
        assertEquals(value, SkillSecretsStore(context).get("probe", "token"))
    }

    private fun seedLegacy(prefs: SharedPreferences, secretKey: String, ivKey: String, marker: String) {
        assertTrue(prefs.edit()
            .putString(secretKey, Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            .putString(ivKey, marker)
            .commit())
    }

    private fun assertEncrypted(prefs: SharedPreferences, secretKey: String, ivKey: String, marker: String) {
        val ciphertext = requireNotNull(prefs.getString(secretKey, null))
        val iv = requireNotNull(prefs.getString(ivKey, null))
        assertNotEquals(marker, iv)
        assertNotEquals(value, ciphertext)
        assertFalse(Base64.decode(ciphertext, Base64.NO_WRAP).contentEquals(value.toByteArray(Charsets.UTF_8)))
        assertEquals(12, Base64.decode(iv, Base64.NO_WRAP).size)
    }
}
