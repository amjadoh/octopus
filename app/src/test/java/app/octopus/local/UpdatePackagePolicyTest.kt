package app.octopus.local
import app.octopus.local.update.UpdatePackagePolicy
import org.junit.Assert.*
import org.junit.Test
class UpdatePackagePolicyTest {
    @Test fun permitsOnlyNewerSamePackageWithSameNonemptySigner() {
        fun accepts(pkg: String = "app.octopus.local", version: Long = 12, signers: Set<String> = setOf("stable")) =
            UpdatePackagePolicy.accepts("app.octopus.local", pkg, 11, version, setOf("stable"), signers)
        assertTrue(accepts())
        assertFalse(accepts(version = 11)); assertFalse(accepts(version = 10))
        assertFalse(accepts(pkg = "app.octopus.local.demo"))
        assertFalse(accepts(signers = setOf("other"))); assertFalse(accepts(signers = emptySet()))
    }
}
