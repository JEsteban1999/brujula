package app.brujula.claude

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ClaudeKeyStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val fakeKey = "sk-ant-test-0000"

    private fun TestScope.store(): Pair<DataStore<Preferences>, ClaudeKeyStore> {
        val ds = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "k.preferences_pb") }
        return ds to ClaudeKeyStore(ds, FakeSecretCipher())
    }

    @Test fun saveReadClear() = runTest {
        val (_, s) = store()
        assertFalse(s.hasKey.first())
        assertNull(s.read())
        s.save(fakeKey)
        assertTrue(s.hasKey.first())
        assertEquals(fakeKey, s.read())
        s.clear()
        assertFalse(s.hasKey.first())
        assertNull(s.read())
    }

    @Test fun storedValueIsNotPlain() = runTest {
        val (ds, s) = store()
        s.save(fakeKey)
        val all = ds.data.first().asMap().values.map { it.toString() }
        assertTrue(all.isNotEmpty())
        assertTrue(all.none { it.contains(fakeKey) })
    }

    @Test fun failedDecryptReturnsNullAndClears() = runTest {
        val (ds, s) = store()
        ds.edit { it[stringPreferencesKey("claude_api_key_enc")] = "garbage" }
        assertTrue(s.hasKey.first())
        assertNull(s.read())
        assertFalse(s.hasKey.first())
    }
}

@RunWith(RobolectricTestRunner::class)
class ManifestPermissionsTest {
    @Test fun requestsInternet() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        assertTrue(info.requestedPermissions.orEmpty().contains("android.permission.INTERNET"))
    }
}
