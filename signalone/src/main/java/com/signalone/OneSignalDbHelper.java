/**
 * Modified MIT License
 *
 * Copyright 2017 OneSignal
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * 1. The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * 2. All copies of substantial portions of the Software may only be used in connection
 * with services provided by OneSignal.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.signalone;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.SystemClock;

import com.signalone.OneSignalDbContract.NotificationTable;

import java.util.ArrayList;
import java.util.List;

public class OneSignalDbHelper extends SQLiteOpenHelper {
   static final int DATABASE_VERSION = 3;
   private static final String DATABASE_NAME = "OneSignal.db";

   private static final String TEXT_TYPE = " TEXT";
   private static final String INT_TYPE = " INTEGER";
   private static final String COMMA_SEP = ",";
   
   private static final int DB_OPEN_RETRY_MAX = 5;
   private static final int DB_OPEN_RETRY_BACKOFF = 400;

   private static final String SQL_CREATE_ENTRIES =
       "CREATE TABLE " + NotificationTable.TABLE_NAME + " (" +
           NotificationTable._ID + " INTEGER PRIMARY KEY," +
           NotificationTable.COLUMN_NAME_NOTIFICATION_ID + TEXT_TYPE + COMMA_SEP +
           NotificationTable.COLUMN_NAME_ANDROID_NOTIFICATION_ID + INT_TYPE + COMMA_SEP +
           NotificationTable.COLUMN_NAME_GROUP_ID + TEXT_TYPE + COMMA_SEP +
           NotificationTable.COLUMN_NAME_COLLAPSE_ID + TEXT_TYPE + COMMA_SEP +
           NotificationTable.COLUMN_NAME_IS_SUMMARY + INT_TYPE + " DEFAULT 0" + COMMA_SEP +
           NotificationTable.COLUMN_NAME_OPENED + INT_TYPE + " DEFAULT 0" + COMMA_SEP +
           NotificationTable.COLUMN_NAME_DISMISSED + INT_TYPE + " DEFAULT 0" + COMMA_SEP +
           NotificationTable.COLUMN_NAME_TITLE + TEXT_TYPE + COMMA_SEP +
           NotificationTable.COLUMN_NAME_MESSAGE + TEXT_TYPE + COMMA_SEP +
           NotificationTable.COLUMN_NAME_FULL_DATA + TEXT_TYPE + COMMA_SEP +
           NotificationTable.COLUMN_NAME_CREATED_TIME + " TIMESTAMP DEFAULT (strftime('%s', 'now'))" + COMMA_SEP +
           NotificationTable.COLUMN_NAME_EXPIRE_TIME + " TIMESTAMP" +
       ");";

   private static final String[] SQL_INDEX_ENTRIES = {
      NotificationTable.INDEX_CREATE_NOTIFICATION_ID,
      NotificationTable.INDEX_CREATE_ANDROID_NOTIFICATION_ID,
      NotificationTable.INDEX_CREATE_GROUP_ID,
      NotificationTable.INDEX_CREATE_COLLAPSE_ID,
      NotificationTable.INDEX_CREATE_CREATED_TIME,
      NotificationTable.INDEX_CREATE_EXPIRE_TIME
   };

   private static OneSignalDbHelper sInstance;

   private static int getDbVersion() {
      return DATABASE_VERSION;
   }

   OneSignalDbHelper(Context context) {
      super(context, DATABASE_NAME, null, getDbVersion());

   }

   public static synchronized OneSignalDbHelper getInstance(Context context) {
      if (sInstance == null)
         sInstance = new OneSignalDbHelper(context.getApplicationContext());
      return sInstance;
   }

   // Retry in-case of rare device issues with opening database.
   // https://github.com/OneSignal/OneSignal-Android-SDK/issues/136
   synchronized SQLiteDatabase getWritableDbWithRetries() {
      int count = 0;
      while(true) {
         try {
            return getWritableDatabase();
         } catch (Throwable t) {
            if (++count >= DB_OPEN_RETRY_MAX)
               throw t;
            SystemClock.sleep(count * DB_OPEN_RETRY_BACKOFF);
         }
      }
   }
   
   synchronized SQLiteDatabase getReadableDbWithRetries() {
      int count = 0;
      while(true) {
         try {
            return getReadableDatabase();
         } catch (Throwable t) {
            if (++count >= DB_OPEN_RETRY_MAX)
               throw t;
            SystemClock.sleep(count * DB_OPEN_RETRY_BACKOFF);
         }
      }
   }

   @Override
   public void onCreate(SQLiteDatabase db) {
      db.execSQL(SQL_CREATE_ENTRIES);
      for (String ind : SQL_INDEX_ENTRIES) {
         db.execSQL(ind);
      }
   }

   @Override
   public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
      try {
         internalOnUpgrade(db, oldVersion);
      } catch (SQLiteException e) {
         // This could throw if rolling back then forward again.
         //   However this shouldn't happen as we clearing the database on onDowngrade
         SignalOne.Log(SignalOne.LOG_LEVEL.ERROR, "Error in upgrade, migration may have already run! Skipping!" , e);
      }
   }
   
   private static void internalOnUpgrade(SQLiteDatabase db, int oldVersion) {
      if (oldVersion < 2)
         upgradeFromV1ToV2(db);

      if (oldVersion < 3)
         upgradeFromV2ToV3(db);
   }

   // Add collapse_id field and index
   private static void upgradeFromV1ToV2(SQLiteDatabase db) {
      safeExecSQL(db,
         "ALTER TABLE " + NotificationTable.TABLE_NAME + " " +
            "ADD COLUMN " + NotificationTable.COLUMN_NAME_COLLAPSE_ID + TEXT_TYPE + ";"
      );
      safeExecSQL(db, NotificationTable.INDEX_CREATE_GROUP_ID);
   }

   // Add expire_time field and index.
   // Also backfills expire_time to create_time + 72 hours
   private static void upgradeFromV2ToV3(SQLiteDatabase db) {
      safeExecSQL(db,
         "ALTER TABLE " + NotificationTable.TABLE_NAME + " " +
            "ADD COLUMN " + NotificationTable.COLUMN_NAME_EXPIRE_TIME + " TIMESTAMP" + ";"
      );

      safeExecSQL(db,
         "UPDATE " + NotificationTable.TABLE_NAME + " " +
            "SET " + NotificationTable.COLUMN_NAME_EXPIRE_TIME +  " = "
                     + NotificationTable.COLUMN_NAME_CREATED_TIME + " + " + NotificationRestorer.DEFAULT_TTL_IF_NOT_IN_PAYLOAD + ";"
      );

      safeExecSQL(db, NotificationTable.INDEX_CREATE_EXPIRE_TIME);
   }

   private static void safeExecSQL(SQLiteDatabase db, String sql) {
      try {
         db.execSQL(sql);
      } catch (SQLiteException e) {
         e.printStackTrace();
      }
   }
   
   @Override
   public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
      SignalOne.Log(SignalOne.LOG_LEVEL.WARN, "SDK version rolled back! Clearing " + DATABASE_NAME + " as it could be in an unexpected state.");
      
      Cursor cursor = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null);
      try {
         List<String> tables = new ArrayList<>(cursor.getCount());
      
         while (cursor.moveToNext())
            tables.add(cursor.getString(0));
      
         for (String table : tables) {
            if (table.startsWith("sqlite_"))
               continue;
            db.execSQL("DROP TABLE IF EXISTS " + table);
         }
      } finally {
         cursor.close();
      }
      
      onCreate(db);
   }
   
   // Could enable WAL in the future but requires Android API 11
   /*
   @Override
   public void onConfigure(SQLiteDatabase db) {
      super.onConfigure(db);
      db.enableWriteAheadLogging();
   }
   */

   static StringBuilder recentUninteractedWithNotificationsWhere() {
      long currentTimeSec = System.currentTimeMillis() / 1_000L;
      long createdAtCutoff = currentTimeSec - 604_800L; // 1 Week back

      StringBuilder where = new StringBuilder(
         NotificationTable.COLUMN_NAME_CREATED_TIME + " > " + createdAtCutoff + " AND " +
         NotificationTable.COLUMN_NAME_DISMISSED    + " = 0 AND " +
         NotificationTable.COLUMN_NAME_OPENED       + " = 0 AND " +
         NotificationTable.COLUMN_NAME_IS_SUMMARY   + " = 0"
      );

      boolean useTtl = SignalOnePrefs.getBool(SignalOnePrefs.PREFS_ONESIGNAL, SignalOnePrefs.PREFS_OS_RESTORE_TTL_FILTER,true);
      if (useTtl)
         where.append(" AND " + NotificationTable.COLUMN_NAME_EXPIRE_TIME + " > " + currentTimeSec);

      return where;
   }
}
