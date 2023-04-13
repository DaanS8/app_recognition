package com.example.myapplication;

import android.Manifest;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_IMAGE_CAPTURE = 1;
    private static final int PERMISSION_REQUEST_CAMERA = 2;
    private static final int MAX_IMAGE_SIZE = 720;
    private static final String BASE_URL = "http://34.77.63.96:8080/";

    private Bitmap capturedImageBitmap;
    private ImageButton searchButton;

    private ProgressDialog progressDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        searchButton = findViewById(R.id.search_button);

        searchButton.setOnClickListener(v -> openCamera());

    }

    private void openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            askForCameraPermission();
        } else {
            Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
                startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE);
            }
        }
    }

    private void processAndSendImage() {
        // Convert and send the image in a background task
        new ProcessImageTask().execute(capturedImageBitmap);
    }

    private void askForCameraPermission() {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, PERMISSION_REQUEST_CAMERA);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openCamera();
            } else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_IMAGE_CAPTURE && resultCode == RESULT_OK) {
            Bundle extras = data.getExtras();
            capturedImageBitmap = (Bitmap) extras.get("data"); // Store the captured image directly

            progressDialog = new ProgressDialog(MainActivity.this);
            progressDialog.setMessage("Processing...");
            progressDialog.show();

            processAndSendImage();
        }
    }


    private class ProcessImageTask extends AsyncTask<Bitmap, Void, Bitmap> {
        private long startTime;

        @Override
        protected void onPreExecute() {
            startTime = SystemClock.elapsedRealtime();
        }

        @Override
        protected Bitmap doInBackground(Bitmap... bitmaps) {
            Bitmap original = bitmaps[0];
            return convertToBlackAndWhite(original);
        }

        @Override
        protected void onPostExecute(Bitmap processedImage) {
            sendImageToServer(processedImage);
        }

        private Bitmap convertToBlackAndWhite(Bitmap original) {
            // Convert the image to black and white
            ColorMatrix colorMatrix = new ColorMatrix();
            colorMatrix.setSaturation(0);
            Paint paint = new Paint();
            paint.setColorFilter(new ColorMatrixColorFilter(colorMatrix));

            int width = original.getWidth();
            int height = original.getHeight();

            // Resize the image if necessary
            if (width >= height && width > MAX_IMAGE_SIZE) {
                float ratio = (float) MAX_IMAGE_SIZE / width;
                height = (int) (height * ratio);
                width = MAX_IMAGE_SIZE;
            } else if (height > MAX_IMAGE_SIZE) {
                float ratio = (float) MAX_IMAGE_SIZE / height;
                width = (int) (width * ratio);
                height = MAX_IMAGE_SIZE;
            }

            Bitmap resizedImage = Bitmap.createScaledBitmap(original, width, height, true);
            Bitmap result = Bitmap.createBitmap(resizedImage.getWidth(), resizedImage.getHeight(), resizedImage.getConfig());
            Canvas canvas = new Canvas(result);
            canvas.drawBitmap(resizedImage, 0, 0, paint); // Apply the black and white conversion here
            return result;
        }


        private void sendImageToServer(Bitmap image) {
                        // Convert the image to a JPEG with the compression rate from the original Python code
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            image.compress(Bitmap.CompressFormat.JPEG, 95, stream); // Use 80 as compression_rate
            byte[] byteArray = stream.toByteArray();

            OkHttpClient client = new OkHttpClient();

            RequestBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("image", "image.jpg",
                            RequestBody.create(MediaType.parse("image/jpeg"), byteArray))
                    .build();

            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(BASE_URL + "api/game_id")
                    .post(requestBody)
                    .build();

            long localProcessingTime = SystemClock.elapsedRealtime() - startTime;

            // Show a loading dialog
            progressDialog.setMessage("Uploading...");

            long serverRequestStartTime = SystemClock.elapsedRealtime();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    runOnUiThread(() -> {
                        progressDialog.dismiss();
                        Toast.makeText(MainActivity.this, "Error uploading image: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    });
                }


                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    runOnUiThread(() -> progressDialog.dismiss());

                    if (response.isSuccessful()) {
                        String responseJson = response.body().string();
                        // Print the response
                        Log.d("Response", responseJson);
                        long serverProcessingTime = SystemClock.elapsedRealtime() - serverRequestStartTime;
                        displayResults(responseJson, localProcessingTime, serverProcessingTime);
                    } else {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "Error: " + response.code(), Toast.LENGTH_SHORT).show());
                    }
                }


            });

        }
    }

    private void displayResults(String responseJson, long localProcessingTime, long serverProcessingTime) {
        runOnUiThread(() -> {
            setContentView(R.layout.result_table);
            TextView localProcessingTimeView = findViewById(R.id.local_processing_time);
            TextView serverProcessingTimeView = findViewById(R.id.server_processing_time);
            TableLayout tableLayout = findViewById(R.id.result_table);

            localProcessingTimeView.setText("Local processing time: " + localProcessingTime + " ms");
            serverProcessingTimeView.setText("Server processing time: " + serverProcessingTime + " ms");

            try {
                JSONObject jsonObject = new JSONObject(responseJson);

                for (Iterator<String> it = jsonObject.keys(); it.hasNext(); ) {
                    String key = it.next();
                    String value = jsonObject.getString(key);
                    double valueDouble = Double.parseDouble(value);

                    TableRow tableRow = new TableRow(MainActivity.this);
                    tableRow.setLayoutParams(new TableRow.LayoutParams(TableRow.LayoutParams.MATCH_PARENT, TableRow.LayoutParams.WRAP_CONTENT));

                    TextView keyTextView = new TextView(MainActivity.this);

                    keyTextView.setText(key);
                    keyTextView.setSingleLine(false);
                    keyTextView.setEllipsize(null);


                    keyTextView.setTextSize(18);
                    TableRow.LayoutParams keyTextViewParams = new TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 0.5f);
                    keyTextView.setLayoutParams(keyTextViewParams);
                    keyTextView.setPadding(8, 8, 8, 8);

                    tableRow.addView(keyTextView);

                    TextView valueTextView = new TextView(MainActivity.this);

                    valueTextView.setText(value.concat(" %"));
                    valueTextView.setTextSize(18);

                    TableRow.LayoutParams valueTextViewParams = new TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 0.5f);
                    valueTextView.setLayoutParams(valueTextViewParams);
                    valueTextView.setPadding(8, 8, 8, 8);
                    valueTextView.setBackgroundColor(getColorForValue(valueDouble));

                    tableRow.addView(valueTextView);

                    tableLayout.addView(tableRow, new TableLayout.LayoutParams(TableLayout.LayoutParams.MATCH_PARENT, TableLayout.LayoutParams.WRAP_CONTENT));

                }

                ImageButton redoSearchButton = findViewById(R.id.redo_search_button);
                redoSearchButton.setOnClickListener(v -> {
                    setContentView(R.layout.activity_main);
                    searchButton = findViewById(R.id.search_button);
                    searchButton.setOnClickListener(v2 -> openCamera());
                });

            } catch (JSONException e) {
                e.printStackTrace();
                Toast.makeText(MainActivity.this, "Error parsing JSON response", Toast.LENGTH_SHORT).show();
            }
        });


    }
    private int getColorForValue(double value) {
        int green = (int) Math.round(255 * (value / 100));
        int gray = (int) Math.round(255 * (1 - (value / 100)));
        return Color.argb(128, gray, green, gray);
    }

}