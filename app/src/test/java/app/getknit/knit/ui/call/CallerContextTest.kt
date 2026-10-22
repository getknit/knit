package app.getknit.knit.ui.call

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric for the app's own English resources: the line is worded by string resources, read as a user would. */
@RunWith(RobolectricTestRunner::class)
class CallerContextTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val stranger = Relationship(contact = false, verified = false, blocked = false, groupsTogether = 0, anyMessages = false)

    @Test
    fun theRelationshipLineCountsGroupsAndNamesNone() {
        assertEquals("Not a contact · no messages yet", relationshipText(context, stranger))
        assertEquals("Not a contact", relationshipText(context, stranger.copy(anyMessages = true)))
        assertEquals("Contact", relationshipText(context, stranger.copy(contact = true, anyMessages = true)))
        assertEquals("Contact · 1 group together", relationshipText(context, stranger.copy(contact = true, groupsTogether = 1)))
        assertEquals(
            "Verified contact · 2 groups together",
            relationshipText(context, stranger.copy(contact = true, verified = true, groupsTogether = 2)),
        )
        assertEquals("Blocked", relationshipText(context, stranger.copy(blocked = true, verified = true, anyMessages = true)))
    }

    @Test
    fun offTheLockScreenTheLineNamesTheGroupsAndCountsPastTwo() {
        val contact = stranger.copy(contact = true, anyMessages = true, groupsTogether = 3)
        assertEquals("Contact · in Climbing", relationshipText(context, contact.copy(groupsTogether = 1), listOf("Climbing")))
        assertEquals("Contact · in Climbing, Lab", relationshipText(context, contact.copy(groupsTogether = 2), listOf("Climbing", "Lab")))
        assertEquals("Contact · in Climbing, Lab +1", relationshipText(context, contact, listOf("Climbing", "Lab", "Zine")))
    }

    @Test
    fun theLockedLineNeverNamesAGroup() {
        val caller = CallerContext(relationship = "Contact · 2 groups together", namedRelationship = "Contact · in Climbing, Lab")
        assertEquals("Contact · 2 groups together", caller.relationshipShown(locked = true))
        assertEquals("Contact · in Climbing, Lab", caller.relationshipShown(locked = false))
        assertEquals("Contact", CallerContext(relationship = "Contact").relationshipShown(locked = false))
    }
}
