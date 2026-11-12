package tv.debloat.mirrorsend;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

/**
 * 转屏用的透明授权页。
 *
 * 安卓 14 起，一个录屏授权只能开一个虚拟屏，复用同一个 MediaProjection 再开第二个
 * 会直接被 SecurityException 拒掉。所以换方向时不能"改造"旧会话，只能走海豚那套：
 * 丢掉旧会话，重新向系统申请一次授权（系统会再弹一次确认），拿到新会话再建新虚拟屏。
 *
 * 这个 Activity 是全透明的，出来只是为了让系统弹那个授权框，点完就自己关掉。
 */
public class ReauthActivity extends Activity {

    private static final int REQ_CAPTURE = 2001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 这个页面是"弹一次系统授权框就收工"的。如果它因为别的原因被重建了，
        // 千万别再弹第二次——那样用户会连着看到两个一样的授权窗口。
        // 已经在弹的话，等旧的那次结果回来就行（系统会把结果送到新实例上）。
        if (savedInstanceState != null) {
            LogHub.add("授权页被重建，不再重复弹框");
            return;
        }

        // 这里只接落盘日志，不要再开一次日志口——端口已经被主界面占了，
        // 再开一次只会白抛 EADDRINUSE
        LogHub.init(this);
        LogHub.add("转屏重新申请授权");

        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            LogHub.add("这个系统没有录屏接口，转屏没法重来");
            finish();
            return;
        }
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
        } catch (Throwable e) {
            LogHub.add("弹不出授权框: " + e);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) {
            finish();
            return;
        }
        if (resultCode != RESULT_OK || data == null) {
            LogHub.add("转屏授权被拒绝，保持原方向继续投");
            // 告诉服务别一直等着，把"正在等授权"的标记清掉，下次转屏还能再问
            Intent svc = new Intent(this, MirrorService.class);
            svc.setAction(MirrorService.ACTION_REBUILD);
            svc.putExtra(MirrorService.EXTRA_REJECTED, true);
            try { startForegroundService(svc); } catch (Throwable ignored) {}
            finish();
            return;
        }

        Intent svc = new Intent(this, MirrorService.class);
        svc.setAction(MirrorService.ACTION_REBUILD);
        svc.putExtra(MirrorService.EXTRA_RESULT_CODE, resultCode);
        svc.putExtra(MirrorService.EXTRA_RESULT_DATA, data);
        try {
            startForegroundService(svc);
            LogHub.add("新授权已交给投屏服务");
        } catch (Throwable e) {
            LogHub.add("递授权失败: " + e);
        }
        finish();
    }
}
