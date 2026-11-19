package app.brujula.claude

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * Un SDK que no encaja con el runtime (en ART: NoSuchMethodError al armar la petición) no tumba la app: termina en un error
 * de configuración no reintentable. Sin red: los parámetros lanzan antes de cualquier petición; la clave es de mentira.
 */
class AnthropicClaudeGatewayTest {
    private val logged = mutableListOf<String>()
    private val originalWarn = DictationCall.warn

    @Before fun captureLog() { DictationCall.warn = { logged += it } }

    @After fun restoreLog() { DictationCall.warn = originalWarn }

    private fun gateway(error: () -> Throwable) = AnthropicClaudeGateway(
        readKey = { "sk-ant-test-0000" },
        dictationParams = { _, _ -> throw error() },
        classificationParams = { _, _ -> throw error() },
        keyTestParams = { throw error() },
    )

    private val configError = GatewayResult.Error(retryable = false, config = true)

    @Test fun noSuchMethodErrorBuildingParamsIsAConfigErrorNotACrash() = runTest {
        val g = gateway { NoSuchMethodError("getAnnotatedReturnType secreto") }
        assertEquals(configError, g.interpret("s", "u"))
        assertEquals(configError, g.classify("s", "u"))
        assertEquals(KeyTestResult.ERROR, g.testKey())
        assertEquals(
            listOf("Dictado falló: NoSuchMethodError", "Clasificación falló: NoSuchMethodError", "Prueba de clave falló: NoSuchMethodError"),
            logged,
        )
        assertFalse("Nunca el mensaje", logged.any { it.contains("secreto") })
    }

    @Test fun initializerAndClassLoadingErrorsAreAlsoConfigErrors() = runTest {
        assertEquals(configError, gateway { ExceptionInInitializerError(RuntimeException("x")) }.interpret("s", "u"))
        assertEquals(configError, gateway { NoClassDefFoundError("x") }.classify("s", "u"))
    }

    @Test fun ordinaryExceptionsKeepTheirMapping() = runTest {
        assertEquals(GatewayResult.Error(retryable = false), gateway { IllegalStateException("x") }.interpret("s", "u"))
    }

    @Test fun noKeyNeverBuildsParams() = runTest {
        val g = AnthropicClaudeGateway(readKey = { null }, dictationParams = { _, _ -> error("no debe llamarse") })
        assertEquals(GatewayResult.NoKey, g.interpret("s", "u"))
    }
}
