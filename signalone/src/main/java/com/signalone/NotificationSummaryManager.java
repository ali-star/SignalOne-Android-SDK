package com.signalone;

import android.app.NotificationManager;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONException;
import org.json.JSONObject;

class NotificationSummaryManager {
   
   // A notification was just dismissed, check if it was a child to a summary notification and update it.
   static void updatePossibleDependentSummaryOnDismiss(Context context, SQLiteDatabase writableDb, int androidNotificationId) {
      Cursor cursor = writableDb.query(
          OneSignalDbContract.NotificationTable.TABLE_NAME,
          new String[] { OneSignalDbContract.NotificationTable.COLUMN_NAME_GROUP_ID }, // retColumn
          OneSignalDbContract.NotificationTable.COLUMN_NAME_ANDROID_NOTIFICATION_ID + " = " + androidNotificationId,
          null, null, null, null);
      
      if (cursor.moveToFirst()) {
         String group = cursor.getString(cursor.getColumnIndex(OneSignalDbContract.NotificationTable.COLUMN_NAME_GROUP_ID));
         cursor.close();
         
         if (group != null)
            updateSummaryNotificationAfterChildRemoved(context, writableDb, group, true);
      }
      else
         cursor.close();
   }
   
   // Called from an opened / dismissed / cancel event of a single notification to update it's parent the summary notification.
   static void updateSummaryNotificationAfterChildRemoved(Context context, SQLiteDatabase writableDb, String group, boolean dismissed) {
      Cursor cursor = null;
      try {
         cursor = internalUpdateSummaryNotificationAfterChildRemoved(context, writableDb, group, dismissed);
      } catch (Throwable t) {
         SignalOne.Log(SignalOne.LOG_LEVEL.ERROR, "Error running updateSummaryNotificationAfterChildRemoved!", t);
      } finally {
         if (cursor != null && !cursor.isClosed())
            cursor.close();
      }
   }
   
   private static Cursor internalUpdateSummaryNotificationAfterChildRemoved(Context context, SQLiteDatabase writableDb, String group, boolean dismissed) {
      Cursor cursor = writableDb.query(
          OneSignalDbContract.NotificationTable.TABLE_NAME,
          new String[] { OneSignalDbContract.NotificationTable.COLUMN_NAME_ANDROID_NOTIFICATION_ID, // return columns
              OneSignalDbContract.NotificationTable.COLUMN_NAME_CREATED_TIME },
          OneSignalDbContract.NotificationTable.COLUMN_NAME_GROUP_ID + " = ? AND " + // Where String
              OneSignalDbContract.NotificationTable.COLUMN_NAME_DISMISSED + " = 0 AND " +
              OneSignalDbContract.NotificationTable.COLUMN_NAME_OPENED + " = 0 AND " +
              OneSignalDbContract.NotificationTable.COLUMN_NAME_IS_SUMMARY + " = 0" ,
          new String[] { group }, // whereArgs
          null, null,
          OneSignalDbContract.NotificationTable._ID + " DESC");   // sort order, new to old);
   
      int notifsInGroup = cursor.getCount();
   
      // If all individual notifications consumed
      //   - Remove summary notification from the shade.
      //   - Mark summary notification as consumed.
      if (notifsInGroup == 0) {
         cursor.close();
   
         Integer androidNotifId = getSummaryNotificationId(writableDb, group);
         if (androidNotifId == null)
            return cursor;
      
         // Remove the summary notification from the shade.
         NotificationManager notificationManager = (NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
         notificationManager.cancel(androidNotifId);
      
         // Mark the summary notification as opened or dismissed.
         ContentValues values = new ContentValues();
         values.put(dismissed ? OneSignalDbContract.NotificationTable.COLUMN_NAME_DISMISSED : OneSignalDbContract.NotificationTable.COLUMN_NAME_OPENED, 1);
         writableDb.update(OneSignalDbContract.NotificationTable.TABLE_NAME,
             values,
             OneSignalDbContract.NotificationTable.COLUMN_NAME_ANDROID_NOTIFICATION_ID + " = " + androidNotifId,
             null);
         return cursor;
      }
   
      // Only a single notification now in the group
      //   - Need to recreate a summary notification so it looks like a normal notifications since we
      //        only have one notification now.
      if (notifsInGroup == 1) {
         cursor.close();
         Integer androidNotifId = getSummaryNotificationId(writableDb, group);
         if (androidNotifId == null)
            return cursor;
         restoreSummary(context, group);
         return cursor;
      }
      
      // 2 or more still left in the group
      //  - Just need to update the summary notification.
      //  - Don't need start a broadcast / service as the extender doesn't support overriding
      //      the summary notification.
      try {
         cursor.moveToFirst();
         Long datetime = cursor.getLong(cursor.getColumnIndex(OneSignalDbContract.NotificationTable.COLUMN_NAME_CREATED_TIME));
         cursor.close();
   
         Integer androidNotifId = getSummaryNotificationId(writableDb, group);
         if (androidNotifId == null)
            return cursor;
         
         NotificationGenerationJob notifJob = new NotificationGenerationJob(context);
         notifJob.restoring = true;
         notifJob.shownTimeStamp = datetime;
      
         JSONObject payload = new JSONObject();
         payload.put("grp", group);
         notifJob.jsonPayload = payload;
      
         GenerateNotification.updateSummaryNotification(notifJob);
      } catch (JSONException e) {}
      
      return cursor;
   }
   
   private static void restoreSummary(Context context, String group) {
      OneSignalDbHelper dbHelper = OneSignalDbHelper.getInstance(context);
      
      Cursor cursor = null;
      
      String[] whereArgs = { group };
      
      try {
         SQLiteDatabase readableDb = dbHelper.getReadableDbWithRetries();
         cursor = readableDb.query(
             OneSignalDbContract.NotificationTable.TABLE_NAME,
             NotificationRestorer.COLUMNS_FOR_RESTORE,
            OneSignalDbContract.NotificationTable.COLUMN_NAME_GROUP_ID + " = ? AND " +
             OneSignalDbContract.NotificationTable.COLUMN_NAME_DISMISSED + " = 0 AND " +
             OneSignalDbContract.NotificationTable.COLUMN_NAME_OPENED + " = 0 AND " +
             OneSignalDbContract.NotificationTable.COLUMN_NAME_IS_SUMMARY + " = 0",
             whereArgs,
             null,                           // group by
             null,                            // filter by row groups
             null
         );
   
         NotificationRestorer.showNotificationsFromCursor(context, cursor, 0);
      } catch (Throwable t) {
         SignalOne.Log(SignalOne.LOG_LEVEL.ERROR, "Error restoring notification records! ", t);
      } finally {
         if (cursor != null && !cursor.isClosed())
            cursor.close();
      }
   }
   
   private static Integer getSummaryNotificationId(SQLiteDatabase writableDb, String group) {
      Integer androidNotifId = null;
      Cursor cursor = null;
      try {
         // Get the Android Notification ID of the summary notification
         cursor = writableDb.query(
             OneSignalDbContract.NotificationTable.TABLE_NAME,
             new String[] { OneSignalDbContract.NotificationTable.COLUMN_NAME_ANDROID_NOTIFICATION_ID }, // retColumn
             OneSignalDbContract.NotificationTable.COLUMN_NAME_GROUP_ID + " = ? AND " + // Where String
                 OneSignalDbContract.NotificationTable.COLUMN_NAME_DISMISSED + " = 0 AND " +
                 OneSignalDbContract.NotificationTable.COLUMN_NAME_OPENED + " = 0 AND " +
                 OneSignalDbContract.NotificationTable.COLUMN_NAME_IS_SUMMARY + " = 1" ,
             new String[] { group }, // whereArgs
             null, null, null);
      
         boolean hasRecord = cursor.moveToFirst();
         if (!hasRecord) {
            cursor.close();
            return null;
         }
         androidNotifId = cursor.getInt(cursor.getColumnIndex(OneSignalDbContract.NotificationTable.COLUMN_NAME_ANDROID_NOTIFICATION_ID));
         cursor.close();
      } catch (Throwable t) {
         SignalOne.Log(SignalOne.LOG_LEVEL.ERROR, "Error getting android notification id for summary notification group: " + group, t);
      } finally {
         if (cursor != null && !cursor.isClosed())
            cursor.close();
      }
      
      return androidNotifId;
   }
}