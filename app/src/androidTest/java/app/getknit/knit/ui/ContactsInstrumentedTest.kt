package app.getknit.knit.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** The contacts screen lists the seeded cast (each row tagged `contact_<nodeId>`, name shown as text). */
@RunWith(AndroidJUnit4::class)
class ContactsInstrumentedTest : SeededUiTest() {
    @Test
    fun listsSeededContacts() {
        launch("contacts")

        awaitTag("contact_samr1v00")
        awaitText("Sam Rivera")
        awaitText("Dani Cho")
    }
}
