package io.anuke.mindustry;

import android.*;
import android.content.*;
import android.content.pm.*;
import android.net.*;
import android.os.*;
import android.provider.Settings.*;
import android.telephony.*;
import io.anuke.arc.*;
import io.anuke.arc.backends.android.surfaceview.*;
import io.anuke.arc.files.*;
import io.anuke.arc.function.*;
import io.anuke.arc.scene.ui.layout.*;
import io.anuke.arc.util.*;
import io.anuke.arc.util.serialization.*;
import io.anuke.mindustry.game.Saves.*;
import io.anuke.mindustry.io.*;
import io.anuke.mindustry.net.Net;
import io.anuke.mindustry.net.*;
import io.anuke.mindustry.ui.dialogs.*;

import java.io.*;
import java.lang.System;
import java.util.*;

import static io.anuke.mindustry.Vars.*;

public class AndroidLauncher extends AndroidApplication{
    public static final int PERMISSION_REQUEST_CODE = 1;
    boolean doubleScaleTablets = true;
    FileChooser chooser;
    Runnable permCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
        config.useImmersiveMode = true;
        config.depth = 0;
        if(doubleScaleTablets && isTablet(this.getContext())){
            UnitScl.dp.addition = 0.5f;
        }

        config.hideStatusBar = true;
        Net.setClientProvider(new ArcNetClient());
        Net.setServerProvider(new ArcNetServer());
        initialize(new ClientLauncher(){

            @Override
            public void hide(){
                moveTaskToBack(true);
            }

            @Override
            public String getUUID(){
                try{
                    String s = Secure.getString(getContext().getContentResolver(), Secure.ANDROID_ID);
                    int len = s.length();
                    byte[] data = new byte[len / 2];
                    for(int i = 0; i < len; i += 2){
                        data[i / 2] = (byte)((Character.digit(s.charAt(i), 16) << 4)
                        + Character.digit(s.charAt(i + 1), 16));
                    }
                    String result = new String(Base64Coder.encode(data));
                    if(result.equals("AAAAAAAAAOA=")) throw new RuntimeException("Bad UUID.");
                    return result;
                }catch(Exception e){
                    return super.getUUID();
                }
            }

            @Override
            public void requestExternalPerms(Runnable callback){
                if(Build.VERSION.SDK_INT < Build.VERSION_CODES.M || (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)){
                    callback.run();
                }else{
                    permCallback = callback;
                    ArrayList<String> perms = new ArrayList<>();
                    if(checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED){
                        perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                    }
                    if(checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED){
                        perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
                    }
                    requestPermissions(perms.toArray(new String[0]), PERMISSION_REQUEST_CODE);
                }
            }

            @Override
            public void shareFile(FileHandle file){
            }

            @Override
            public void showFileChooser(String text, String content, Consumer<FileHandle> cons, boolean open, Predicate<String> filetype){
                chooser = new FileChooser(text, file -> filetype.test(file.extension().toLowerCase()), open, cons);
                if(Build.VERSION.SDK_INT < Build.VERSION_CODES.M || (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)){
                    chooser.show();
                    chooser = null;
                }else{
                    ArrayList<String> perms = new ArrayList<>();
                    if(checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED){
                        perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                    }
                    if(checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED){
                        perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
                    }
                    requestPermissions(perms.toArray(new String[0]), PERMISSION_REQUEST_CODE);
                }
            }

            @Override
            public void beginForceLandscape(){
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            }

            @Override
            public void endForceLandscape(){
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER);
            }

            @Override
            public boolean canDonate(){
                return true;
            }
        }, config);
        checkFiles(getIntent());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults){
        if(requestCode == PERMISSION_REQUEST_CODE){
            for(int i : grantResults){
                if(i != PackageManager.PERMISSION_GRANTED) return;
            }
            if(chooser != null){
                Core.app.post(chooser::show);
            }
            if(permCallback != null){
                Core.app.post(permCallback);
                permCallback = null;
            }
        }
    }

    private void checkFiles(Intent intent){
        try{
            Uri uri = intent.getData();
            if(uri != null){
                File myFile = null;
                String scheme = uri.getScheme();
                if(scheme.equals("file")){
                    String fileName = uri.getEncodedPath();
                    myFile = new File(fileName);
                }else if(!scheme.equals("content")){
                    //error
                    return;
                }
                boolean save = uri.getPath().endsWith(saveExtension);
                boolean map = uri.getPath().endsWith(mapExtension);
                InputStream inStream;
                if(myFile != null) inStream = new FileInputStream(myFile);
                else inStream = getContentResolver().openInputStream(uri);
                Core.app.post(() -> Core.app.post(() -> {
                    if(save){ //open save
                        System.out.println("Opening save.");
                        FileHandle file = Core.files.local("temp-save." + saveExtension);
                        file.write(inStream, false);
                        if(SaveIO.isSaveValid(file)){
                            try{
                                SaveSlot slot = control.saves.importSave(file);
                                ui.load.runLoadSave(slot);
                            }catch(IOException e){
                                ui.showError(Core.bundle.format("save.import.fail", Strings.parseException(e, true)));
                            }
                        }else{
                            ui.showError("$save.import.invalid");
                        }
                    }else if(map){ //open map
                        FileHandle file = Core.files.local("temp-map." + mapExtension);
                        file.write(inStream, false);
                        Core.app.post(() -> {
                            System.out.println("Opening map.");
                            if(!ui.editor.isShown()){
                                ui.editor.show();
                            }
                            ui.editor.beginEditMap(file);
                        });
                    }
                }));
            }
        }catch(IOException e){
            e.printStackTrace();
        }
    }

    private boolean isTablet(Context context){
        TelephonyManager manager = (TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);
        return manager != null && manager.getPhoneType() == TelephonyManager.PHONE_TYPE_NONE;
    }
}
