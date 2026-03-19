package com.sms2drive;

import android.content.Context;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class DriveUploadHelper {

    private static final String TARGET_FOLDER_ID = "1r1aHFE7omHLYUgjCUdRXWoD1yagWS_0Z";

    private final Drive driveService;
    private final Executor executor = Executors.newSingleThreadExecutor();

    public DriveUploadHelper(Context context) {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(context);
        GoogleAccountCredential credential = GoogleAccountCredential.usingOAuth2(
                context, Collections.singleton(DriveScopes.DRIVE));
        credential.setSelectedAccount(account.getAccount());

        driveService = new Drive.Builder(
                new NetHttpTransport(),
                new GsonFactory(),
                credential)
                .setApplicationName("SMS2Drive")
                .build();
    }

    public void uploadFile(String content, String fileName) {
        executor.execute(() -> {
            try {
                uploadFileSync(content, fileName);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    public void uploadFileSync(String content, String fileName) throws Exception {
        // DriveReadHelper 캐시에서 파일 ID 가져오기 (공유 캐시)
        String existingFileId = DriveReadHelper.getCachedFileId(fileName);
        if (existingFileId == null) {
            existingFileId = findFile(fileName);
        }

        InputStream inputStream = new ByteArrayInputStream(content.getBytes("UTF-8"));
        com.google.api.client.http.InputStreamContent mediaContent =
                new com.google.api.client.http.InputStreamContent("text/plain", inputStream);

        if (existingFileId != null) {
            // 기존 파일 업데이트 - ID 유지
            driveService.files().update(existingFileId, null, mediaContent).execute();
            // 캐시 갱신 (ID는 동일하게 유지)
            DriveReadHelper.setCachedFileId(fileName, existingFileId);
        } else {
            // 새 파일 생성
            File metadata = new File();
            metadata.setName(fileName);
            metadata.setParents(Arrays.asList(TARGET_FOLDER_ID));
            File created = driveService.files().create(metadata, mediaContent)
                    .setFields("id")
                    .execute();
            // 새 ID를 캐시에 저장
            DriveReadHelper.setCachedFileId(fileName, created.getId());
        }
    }

    private String findFile(String fileName) {
        try {
            FileList result = driveService.files().list()
                    .setQ("name='" + fileName + "' and '"
                            + TARGET_FOLDER_ID + "' in parents and trashed=false")
                    .setSpaces("drive")
                    .setFields("files(id)")
                    .execute();
            if (!result.getFiles().isEmpty()) {
                return result.getFiles().get(0).getId();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
