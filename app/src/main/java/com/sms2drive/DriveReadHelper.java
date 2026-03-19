package com.sms2drive;

import android.content.Context;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.FileList;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class DriveReadHelper {

    private static final String TARGET_FOLDER_ID = "1r1aHFE7omHLYUgjCUdRXWoD1yagWS_0Z";

    // 파일명 → 파일ID 캐시 (앱 전체 공유)
    private static final Map<String, String> fileIdCache = new HashMap<>();

    private final Drive driveService;
    private final Executor executor = Executors.newSingleThreadExecutor();

    public interface ReadCallback {
        void onSuccess(String content);
        void onFailure(String error);
    }

    public DriveReadHelper(Context context) {
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

    public void readFile(String fileName, ReadCallback callback) {
        executor.execute(() -> {
            try {
                // 캐시된 파일 ID 사용, 없으면 검색
                String fileId = fileIdCache.get(fileName);
                if (fileId == null) {
                    fileId = findFile(fileName);
                    if (fileId == null) {
                        callback.onFailure("파일 없음");
                        return;
                    }
                    fileIdCache.put(fileName, fileId);
                }

                String content = readById(fileId);
                if (content == null) {
                    // 파일 ID가 만료됐을 수 있으므로 캐시 삭제 후 재검색
                    fileIdCache.remove(fileName);
                    fileId = findFile(fileName);
                    if (fileId == null) {
                        callback.onFailure("파일 없음");
                        return;
                    }
                    fileIdCache.put(fileName, fileId);
                    content = readById(fileId);
                }

                if (content != null) {
                    callback.onSuccess(content);
                } else {
                    callback.onFailure("읽기 실패");
                }

            } catch (Exception e) {
                callback.onFailure(e.getMessage());
            }
        });
    }

    private String readById(String fileId) {
        try {
            InputStream inputStream = driveService.files()
                    .get(fileId)
                    .setAlt("media")  // ← 직접 미디어 스트림으로 읽기 (더 빠름)
                    .executeMediaAsInputStream();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(inputStream, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // 캐시 공개 접근자 (DriveUploadHelper와 공유)
    public static String getCachedFileId(String fileName) {
        return fileIdCache.get(fileName);
    }

    public static void setCachedFileId(String fileName, String fileId) {
        fileIdCache.put(fileName, fileId);
    }

    public static void invalidateCache(String fileName) {
        fileIdCache.remove(fileName);
    }

    public static void invalidateAllCache() {
        fileIdCache.clear();
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
