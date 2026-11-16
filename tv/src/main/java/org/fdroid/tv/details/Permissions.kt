package org.fdroid.tv.details

import org.fdroid.index.v2.PermissionV2
import org.fdroid.tv.R

/**
 * One row of the permissions section: the store groups platform permissions
 * into a handful of buckets, each with its own icon, title and description.
 */
internal data class PermissionBucket(
    val id: Int,
    val iconRes: Int,
    val titleRes: Int,
    val descriptionRes: Int,
)

internal data class PermissionGroup(
    val bucket: PermissionBucket,
    val permissions: List<PermissionV2>,
)

internal object Permissions {

    /** The bucket used for permissions that belong to no specific group. */
    const val OTHER_ID = 16

    private val buckets = listOf(
        PermissionBucket(
            0,
            R.drawable.ic_perm_in_app_purchases,
            R.string.permission_bucket_in_app_purchases,
            R.string.permission_bucket_in_app_purchases_desc,
        ),
        PermissionBucket(
            1,
            R.drawable.ic_perm_history,
            R.string.permission_bucket_device_history,
            R.string.permission_bucket_device_history_desc,
        ),
        PermissionBucket(
            2,
            R.drawable.ic_perm_data_setting,
            R.string.permission_bucket_cellular,
            R.string.permission_bucket_cellular_desc,
        ),
        PermissionBucket(
            3,
            R.drawable.ic_perm_identity,
            R.string.permission_bucket_identity,
            R.string.permission_bucket_identity_desc,
        ),
        PermissionBucket(
            4,
            R.drawable.ic_perm_cal,
            R.string.permission_bucket_calendar,
            R.string.permission_bucket_calendar_desc,
        ),
        PermissionBucket(
            5,
            R.drawable.ic_perm_contacts,
            R.string.permission_bucket_contacts,
            R.string.permission_bucket_contacts_desc,
        ),
        PermissionBucket(
            6,
            R.drawable.ic_perm_location,
            R.string.permission_bucket_location,
            R.string.permission_bucket_location_desc,
        ),
        PermissionBucket(
            7,
            R.drawable.ic_perm_messaging,
            R.string.permission_bucket_sms,
            R.string.permission_bucket_sms_desc,
        ),
        PermissionBucket(
            8,
            R.drawable.ic_perm_phone,
            R.string.permission_bucket_phone,
            R.string.permission_bucket_phone_desc,
        ),
        PermissionBucket(
            9,
            R.drawable.ic_perm_media,
            R.string.permission_bucket_media,
            R.string.permission_bucket_media_desc,
        ),
        PermissionBucket(
            10,
            R.drawable.ic_perm_camera,
            R.string.permission_bucket_camera,
            R.string.permission_bucket_camera_desc,
        ),
        PermissionBucket(
            11,
            R.drawable.ic_perm_microphone,
            R.string.permission_bucket_microphone,
            R.string.permission_bucket_microphone_desc,
        ),
        PermissionBucket(
            12,
            R.drawable.ic_perm_scan_wifi,
            R.string.permission_bucket_wifi,
            R.string.permission_bucket_wifi_desc,
        ),
        PermissionBucket(
            13,
            R.drawable.ic_perm_bluetooth,
            R.string.permission_bucket_bluetooth,
            R.string.permission_bucket_bluetooth_desc,
        ),
        PermissionBucket(
            14,
            R.drawable.ic_perm_deviceid,
            R.string.permission_bucket_device_id,
            R.string.permission_bucket_device_id_desc,
        ),
        PermissionBucket(
            15,
            R.drawable.ic_perm_body_motion,
            R.string.permission_bucket_wearables,
            R.string.permission_bucket_wearables_desc,
        ),
        PermissionBucket(
            OTHER_ID,
            R.drawable.ic_perm_unknown,
            R.string.permission_bucket_other,
            R.string.permission_bucket_other_desc,
        ),
    )

    private val bucketOf: Map<String, Int> = buildMap {
        fun bucket(id: Int, vararg names: String) = names.forEach { put(it, id) }

        bucket(0, "com.android.vending.BILLING")
        bucket(
            1,
            "android.permission.READ_LOGS",
            "android.permission.GET_TASKS",
            "android.permission.DUMP",
            "com.android.browser.permission.READ_HISTORY_BOOKMARKS",
        )
        bucket(2, "android.permission.WRITE_APN_SETTINGS")
        bucket(
            3,
            "android.permission.GET_ACCOUNTS",
            "android.permission.MANAGE_ACCOUNTS",
            "android.permission.READ_PROFILE",
            "android.permission.WRITE_PROFILE",
        )
        bucket(4, "android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR")
        bucket(5, "android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS")
        bucket(
            6,
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_LOCATION_EXTRA_COMMANDS",
            "android.permission.ACCESS_GPS",
        )
        bucket(
            7,
            "android.permission.RECEIVE_SMS",
            "android.permission.READ_SMS",
            "android.permission.WRITE_SMS",
            "android.permission.SEND_SMS",
            "android.permission.RECEIVE_MMS",
            "android.permission.RECEIVE_WAP_PUSH",
        )
        bucket(
            8,
            "android.permission.CALL_PHONE",
            "android.permission.WRITE_CALL_LOG",
            "android.permission.READ_CALL_LOG",
            "android.permission.CALL_PRIVILEGED",
            "android.permission.PROCESS_OUTGOING_CALLS",
            "android.permission.MODIFY_PHONE_STATE",
        )
        bucket(
            9,
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.MOUNT_FORMAT_FILESYSTEMS",
            "android.permission.MOUNT_UNMOUNT_FILESYSTEMS",
        )
        bucket(10, "android.permission.CAMERA", "android.permission.RECORD_VIDEO")
        bucket(11, "android.permission.RECORD_AUDIO")
        bucket(12, "android.permission.ACCESS_WIFI_STATE")
        bucket(13, "android.permission.BLUETOOTH_ADMIN")
        bucket(14, "android.permission.READ_PHONE_STATE")
        bucket(15, "android.permission.BODY_SENSORS")
    }

    /**
     * Groups the given permissions the way the store does, dropping the buckets
     * no permission falls into and keeping the store's display order.
     */
    fun groupsFor(permissions: List<PermissionV2>): List<PermissionGroup> =
        buckets.mapNotNull { bucket ->
            val inBucket = permissions.filter { (bucketOf[it.name] ?: OTHER_ID) == bucket.id }
            if (inBucket.isEmpty()) null else PermissionGroup(bucket, inBucket)
        }

    /** Turns `android.permission.ACCESS_WIFI_STATE` into `Access wifi state`. */
    fun humanized(permissionName: String): String {
        val short = permissionName.substringAfterLast('.').replace('_', ' ').lowercase()
        return short.replaceFirstChar { it.uppercase() }
            .replace("Wifi", "Wi-Fi")
            .replace("Sms", "SMS")
    }
}
