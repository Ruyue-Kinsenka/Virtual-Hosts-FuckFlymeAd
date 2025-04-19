package com.github.xfalcon.vhosts;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class AboutActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_about);

        setupClickListeners();

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    private void setupClickListeners() {
        // 微信
        findViewById(R.id.wechat_item).setOnClickListener(v ->
                showQRCodeDialog(R.drawable.ic_wechat_qrcode));

        // 支付宝
        findViewById(R.id.alipay_item).setOnClickListener(v ->
                showQRCodeDialog(R.drawable.ic_alipay_qrcode));

        // 爱发电
        findViewById(R.id.afdian_item).setOnClickListener(v ->
                openUrl("https://afdian.com/a/ruyue_kinsenka"));

        // 基于项目
        findViewById(R.id.project_item).setOnClickListener(v ->
                openUrl("https://github.com/x-falcon/Virtual-Hosts"));
    }

    private void showQRCodeDialog(int qrCodeResId) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_qrcode, null);

        ImageView ivQRCode = dialogView.findViewById(R.id.iv_qrcode);
        ivQRCode.setImageResource(qrCodeResId);

        builder.setView(dialogView)
                .setPositiveButton("关闭", (dialog, which) -> dialog.dismiss());

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private void openUrl(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse(url));
        startActivity(intent);
    }
}