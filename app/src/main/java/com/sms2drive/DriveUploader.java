package com.sms2drive;

import android.content.Context;
import java.io.File;
import java.io.FileWriter;

public class DriveUploader {

    public static void upload(Context context, String text) {

        try {

            File file = new File(context.getFilesDir(), "sms_log.txt");

            FileWriter writer = new FileWriter(file, true);

            writer.append(text);

            writer.close();

        } catch (Exception e) {

            e.printStackTrace();

        }
    }
}