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

import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.support.annotation.RequiresApi;

import com.signalone.shortcutbadger.ShortcutBadger;

import static com.signalone.NotificationLimitManager.MAX_NUMBER_OF_NOTIFICATIONS_STR;


class BadgeCountUpdater {

   // Cache for manifest setting.
   private static int badgesEnabled = -1;

   private static boolean areBadgeSettingsEnabled(Context context) {
      if (badgesEnabled != -1)
         return (badgesEnabled == 1);

      try {
         ApplicationInfo ai = context.getPackageManager().getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
         Bundle bundle = ai.metaData;
         if (bundle != null) {
            String defaultStr = bundle.getString("com.onesignal.BadgeCount");
            badgesEnabled = "DISABLE".equals(defaultStr) ? 0 : 1;
         }
         else
            badgesEnabled = 1;
      } catch (Throwable t) {
         badgesEnabled = 0;
         SignalOne.Log(SignalOne.LOG_LEVEL.ERROR, "Error reading meta-data tag 'com.onesignal.BadgeCount'. Disabling badge setting.", t);
      }

      return (badgesEnabled == 1);
   }
   
   private static boolean areBadgesEnabled(Context context) {
      return areBadgeSettingsEnabled(context) && OSUtils.areNotificationsEnabled(context);
   }

   static void update(SQLiteDatabase readableDb, Context context) {
      if (!areBadgesEnabled(context))
         return;

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
         updateStandard(context);
      else
         updateFallback(readableDb, context);
   }

   @RequiresApi(api = Build.VERSION_CODES.M)
   private static void updateStandard(Context context) {
      NotificationManager notifManager = (NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
      StatusBarNotification[] activeNotifs = notifManager.getActiveNotifications();

      int runningCount = 0;
      for (StatusBarNotification activeNotif : activeNotifs) {
         if (NotificationLimitManager.isGroupSummary(activeNotif))
            continue;
         runningCount++;
      }

      updateCount(runningCount, context);
   }

   private static void updateFallback(SQLiteDatabase readableDb, Context context) {
      Cursor cursor = readableDb.query(
         OneSignalDbContract.NotificationTable.TABLE_NAME,
         null,
         OneSignalDbHelper.recentUninteractedWithNotificationsWhere().toString(),
         null,                                                    // Where args
         null,                                                    // group by
         null,                                                    // filter by row groups
         null,                                                     // sort order, new to old
         MAX_NUMBER_OF_NOTIFICATIONS_STR
      );

      int notificationCount = cursor.getCount();
      cursor.close();

      updateCount(notificationCount, context);
   }

   static void updateCount(int count, Context context) {
      if (!areBadgeSettingsEnabled(context))
         return;

      // Can throw if badges are not support on the device.
      //  Or app does not have a default launch Activity.
      try {
         ShortcutBadger.applyCountOrThrow(context, count);
      } catch(Throwable t) {}
   }
}
