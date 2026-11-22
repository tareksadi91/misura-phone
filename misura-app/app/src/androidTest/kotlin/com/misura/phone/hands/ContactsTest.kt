package com.misura.phone.hands

import android.app.UiAutomation
import android.content.ContentProviderOperation
import android.os.ParcelFileDescriptor
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ContactsTest {
    @Test fun readsFixtureNamePhoneTypeAndEmail() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        val ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        listOf("READ_CONTACTS", "WRITE_CONTACTS").forEach { permission ->
            ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("pm grant ${ctx.packageName} android.permission.$permission")).use { it.readBytes() }
        }
        val resolver = ctx.contentResolver
        val name = "Misura fixture ${UUID.randomUUID()}"
        val number = "+12025550100"
        val email = "fixture@example.invalid"
        val operations = arrayListOf(
            ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                .withValue(RawContacts.ACCOUNT_TYPE, null).withValue(RawContacts.ACCOUNT_NAME, null)
                .withValue(RawContacts.AGGREGATION_MODE, RawContacts.AGGREGATION_MODE_DISABLED).build(),
            ContentProviderOperation.newInsert(Data.CONTENT_URI).withValueBackReference(Data.RAW_CONTACT_ID, 0)
                .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE).withValue(StructuredName.DISPLAY_NAME, name).build(),
            ContentProviderOperation.newInsert(Data.CONTENT_URI).withValueBackReference(Data.RAW_CONTACT_ID, 0)
                .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE).withValue(Phone.NUMBER, number)
                .withValue(Phone.TYPE, Phone.TYPE_CUSTOM).withValue(Phone.LABEL, "Fixture line").build(),
            ContentProviderOperation.newInsert(Data.CONTENT_URI).withValueBackReference(Data.RAW_CONTACT_ID, 0)
                .withValue(Data.MIMETYPE, Email.CONTENT_ITEM_TYPE).withValue(Email.ADDRESS, email)
                .withValue(Email.TYPE, Email.TYPE_WORK).build()
        )
        val raw = checkNotNull(resolver.applyBatch(ContactsContract.AUTHORITY, operations)[0].uri)
        try {
            val tool = Contacts(ctx)
            val result = tool.run(buildJsonObject { put("name", name) })
            assertFalse(result.toString(), "error" in result)
            assertEquals("1 person", tool.summary(result))
            val person = result.getValue("people").jsonArray.single().jsonObject
            assertEquals(name, person.getValue("name").jsonPrimitive.content)
            val phone = person.getValue("phones").jsonArray.single().jsonObject
            assertEquals(number, phone.getValue("number").jsonPrimitive.content)
            assertEquals("Fixture line", phone.getValue("type").jsonPrimitive.content)
            assertEquals(listOf(email), person.getValue("emails").jsonArray.map { it.jsonPrimitive.content })
            val missing = tool.run(buildJsonObject { put("name", "missing-${UUID.randomUUID()}") })
            assertEquals(0, missing.getValue("people").jsonArray.size)
            assertEquals("0 people", tool.summary(missing))
            assertEquals("name required", tool.run(buildJsonObject {}).getValue("error").jsonPrimitive.content)
        } finally {
            resolver.delete(raw, null, null)
        }
    }
}
