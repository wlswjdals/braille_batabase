package com.example.braille_batabase;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import androidx.annotation.NonNull;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final int PICK_FILE_REQUEST = 1;  // 파일 선택 요청 코드
    private FirebaseStorage mStorage;
    private StorageReference mStorageRef;
    private DatabaseReference mDatabase;

    private static final String TAG = "BluetoothApp";
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothDevice hc06Device;
    private BluetoothSocket bluetoothSocket;
    private OutputStream outputStream;
    private InputStream inputStream;

    private static final int BLUETOOTH_PERMISSIONS_REQUEST = 1;

    private final UUID HC06_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"); // HC-06 기본 UUID
    TextView textView;
    TextView textView3;
    boolean connected;
    private DatabaseReference messageRef;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Firebase Storage 및 Database 초기화
        mStorage = FirebaseStorage.getInstance();
        mStorageRef = mStorage.getReference();
        mDatabase = FirebaseDatabase.getInstance().getReference();
        textView = findViewById(R.id.textView2);
        textView3 = findViewById(R.id.textView);

        connected = false;

        Button connectButton = findViewById(R.id.connectButton);

        // 업로드 버튼 클릭 시 파일 선택
        Button uploadButton = findViewById(R.id.uploadButton);

        checkBluetoothPermissions(); // 권한 요청

        uploadButton.setOnClickListener(v -> openFileChooser());

        // 연결 버튼
        connectButton.setOnClickListener(v -> connectToHC06());

        // Firebase 데이터 가져오기 버튼 클릭 시 데이터 가져오기
        //Button fetchDataButton = findViewById(R.id.fetchDataButton);
        //fetchDataButton.setOnClickListener(v -> fetchDataFromFirebase());

        messageRef = FirebaseDatabase.getInstance().getReference("message");

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "블루투스를 지원하지 않는 기기입니다.", Toast.LENGTH_SHORT).show();
            finish();
        }

        Toast.makeText(this, "먼저 블루투스 연결 버튼을 눌러주세요", Toast.LENGTH_SHORT).show();

        messageRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                // "message" 경로의 값이 변경되었을 때 호출
                if(connected) {
                    if (snapshot.exists()) {
                        String message = snapshot.getValue(String.class); // 데이터 가져오기
                        if (message != null) {
                            Toast.makeText(MainActivity.this, "새 메시지: " + message, Toast.LENGTH_LONG).show();
                            sendData(message);
                            textView.setText(message);
                        }
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                // 데이터 읽기 실패 시 호출
                Log.e("DatabaseError", "Database error: " + error.getMessage());
            }
        });

    }

    // 파일 선택을 위한 Intent
    private void openFileChooser() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");  // 모든 파일 형식 선택
        intent.addCategory(Intent.CATEGORY_OPENABLE);  // 열 수 있는 파일만 표시
        startActivityForResult(intent, PICK_FILE_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PICK_FILE_REQUEST && resultCode == RESULT_OK && data != null) {
            // 파일 URI 가져오기
            Uri fileUri = data.getData();

            if (fileUri != null) {
                // 파일 업로드 실행
                uploadFile(fileUri);
            }
        }
    }
    // Colab에 파일 업로드
    private void uploadFile(Uri fileUri) {
        if (fileUri != null) {
            // 파일 확장자 가져오기
            String fileExtension = getFileExtension(fileUri);

            if (fileExtension != null) {
                // 고정된 파일 이름 + 확장자
                String fixedFileName = "uploaded_file." + fileExtension;

                // 파일 참조 생성
                StorageReference fileRef = mStorageRef.child("uploads/" + fixedFileName);

                // 파일 업로드
                fileRef.putFile(fileUri)
                        .addOnSuccessListener(taskSnapshot -> {
                            Toast.makeText(MainActivity.this, "파일 업로드 성공!", Toast.LENGTH_SHORT).show();

                            // 업로드 성공 후 다운로드 URL 가져오기
                            fileRef.getDownloadUrl()
                                    .addOnSuccessListener(uri -> {
                                        String downloadUrl = uri.toString();

                                        // Realtime Database에 파일 메타데이터 저장
                                        FileMetadata metadata = new FileMetadata(fixedFileName, downloadUrl);
                                        mDatabase.child("files").push().setValue(metadata)
                                                .addOnCompleteListener(task -> {
                                                    if (task.isSuccessful()) {
                                                        Toast.makeText(MainActivity.this, "메타데이터 저장 성공!", Toast.LENGTH_SHORT).show();
                                                    } else {
                                                        Toast.makeText(MainActivity.this, "메타데이터 저장 실패", Toast.LENGTH_SHORT).show();
                                                    }
                                                });
                                    })
                                    .addOnFailureListener(e -> Toast.makeText(MainActivity.this, "다운로드 URL 가져오기 실패", Toast.LENGTH_SHORT).show());
                        })
                        .addOnFailureListener(e -> Toast.makeText(MainActivity.this, "파일 업로드 실패: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            } else {
                Toast.makeText(this, "파일 확장자를 가져올 수 없습니다.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private String getFileExtension(Uri uri) {
        String extension = null;
        if (uri.getScheme().equals("content")) {
            Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    String fileName = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME));
                    if (fileName != null && fileName.contains(".")) {
                        extension = fileName.substring(fileName.lastIndexOf(".") + 1);
                    }
                }
            }
        } else if (uri.getScheme().equals("file")) {
            String filePath = uri.getPath();
            if (filePath != null && filePath.contains(".")) {
                extension = filePath.substring(filePath.lastIndexOf(".") + 1);
            }
        }
    }
    //파일의 확장자를 얻는 메소드
    public static class FileMetadata {
        public String fileName;
        public String fileUrl;

        public FileMetadata(String fileName, String fileUrl) {
            this.fileName = fileName;
            this.fileUrl = fileUrl;
        }
    }

    private void connectToHC06() {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling requestPermissions here if needed
            return;
        }
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        if (pairedDevices.size() > 0) {
            for (BluetoothDevice device : pairedDevices) {
                if (device.getName().equals("HC-06")) { // HC-06 이름에 맞게 설정
                    hc06Device = device;
                    break;
                }
            }
        }

        if (hc06Device != null) {
            try {
                bluetoothSocket = hc06Device.createRfcommSocketToServiceRecord(HC06_UUID);
                bluetoothSocket.connect();
                outputStream = bluetoothSocket.getOutputStream();
                inputStream = bluetoothSocket.getInputStream();
                Toast.makeText(this, "연결 됐습니다. 업로드 버튼을 눌러 자료를 업로드 하세요", Toast.LENGTH_SHORT).show();
                connected = true;
                startReading();
            } catch (IOException e) {
                Log.e(TAG, "HC-06 연결 실패: " + e.getMessage());
                Toast.makeText(this, "연결 실패 다시 시도해 주세요", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "페어링되지 않았습니다. 다시 시도해 주세요", Toast.LENGTH_SHORT).show();
        }
    }
    //아두이노의 블루투스과 연결하는 메소드
    private void sendData(String data) { //블루투스 통해 아두이노로 보내는 함수
        try {
            outputStream.write(data.getBytes());
            Toast.makeText(this, "데이터 전송 완료", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Log.e(TAG, "데이터 전송 실패: " + e.getMessage());
            Toast.makeText(this, "데이터 전송 실패", Toast.LENGTH_SHORT).show();
        }
    }
    //아두이노에 text를 전송하는 메소드

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == BLUETOOTH_PERMISSIONS_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "블루투스 권한 허용됨", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "블루투스 권한 거부됨. 기능이 제한됩니다.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startReading() { //블루투스 통해 아두이노에서 온 문자 받기
        Thread readThread = new Thread(() -> {
            int bytes;
            while (true) {
                try {
                    String readMessage = new String(buffer, 0, bytes);

                    // UI 업데이트는 UI 스레드에서 처리해야 함
                    runOnUiThread(() -> {
                        // 수신한 데이터를 텍스트뷰에 표시
                        textView.setText(readMessage);
                        // 토스트로 메시지 표시
                        Toast.makeText(MainActivity.this, readMessage, Toast.LENGTH_SHORT).show();
                    });

                    // 수신한 데이터를 로그에 출력
                    Log.d(TAG, "수신한 데이터: " + readMessage);

                    // 수신한 데이터를 처리하는 로직을 여기에 추가
                } 
            }
        });
    }
}
