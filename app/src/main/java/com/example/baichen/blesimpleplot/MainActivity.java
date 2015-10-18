package com.example.baichen.blesimpleplot;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {
    private TextView display = null;

    // Bluetooth adapters and scan
    private BluetoothManager mBluetoothManager = null;
    private BluetoothAdapter mBluetoothAdapter = null;
    private boolean mScanning = false;
    private Handler mHandler = null;
    private static final long SCAN_PERIOD = 2000;
    // Bluetooth device information
    private BluetoothDevice mBluetoothDevice = null;
    private BluetoothGatt mBluetoothGatt = null;
    private BluetoothGattCharacteristic mBluetoothGattCharacteristicAA71 = null;
    private BluetoothGattCharacteristic mBluetoothGattCharacteristicAA72 = null;
    private List<BluetoothGattDescriptor> mBluetoothGattDescriptorsAA71 = null;
    private List<BluetoothGattDescriptor> mBluetoothGattDescriptorsAA72 = null;
    // Three descriptors are used in Quan's current firmware, with the following functions:
    // 1. Enable/disable remote listening (0x2902)
    // 2. Receive Notifications with data (0x2901)
    // 3. Start Recording command (looks like writing directly to 0xAA72)
    // Reference: https://developer.bluetooth.org/gatt/descriptors/Pages/DescriptorViewer.aspx?u=org.bluetooth.descriptor.gatt.client_characteristic_configuration.xml
    private BluetoothGattDescriptor mBluetoothGattDescriptorCCC = null;
    private BluetoothGattDescriptor mBluetoothGattDescriptorGeneralData = null;

    private int mConnectionState = STATE_DISCONNECTED;
    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;

    // Bluetooth Received Data Logging & Processing
    private long receivedPacketsCount = 0;
    private long receivedBytesCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                        .setAction("Action", null).show();
            }
        });

        // custom codes here
        display = (TextView) findViewById(R.id.display);
        display.setMovementMethod(new ScrollingMovementMethod());

        final Button btn_scan = (Button) findViewById(R.id.btn_scan);
        final Button btn_connect = (Button) findViewById(R.id.btn_connect);
        final Button btn_disconnect = (Button) findViewById(R.id.btn_disconnect);
        final Switch sw = (Switch) findViewById(R.id.sw);
        final Button btn_send = (Button) findViewById(R.id.btn_send);
        final Button btn_exit = (Button) findViewById(R.id.btn_exit);
        btn_scan.setOnClickListener( this );
        btn_connect.setOnClickListener( this );
        btn_disconnect.setOnClickListener( this );
        sw.setOnClickListener( this );
        btn_send.setOnClickListener( this);
        btn_exit.setOnClickListener(this);

        // Initialize Bluetooth Adapter
        mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = mBluetoothManager.getAdapter();
        mHandler = new Handler();
        // Ensures Bluetooth is available on the device and it is enabled. If not,
        // displays a message requesting user permission to enable Bluetooth.
        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
            print("Please enable Bluetooth Adapter in settings");
        } else {
            print("Bluetooth Adapter OK!");
        }

    }

    // Button Action Listeners
    @Override
    public void onClick(View v) {
        switch(v.getId()){
            case R.id.btn_scan:
                print("Scanning BLE devices ...");
                ScanBLEDevices();
                break;

            case R.id.btn_connect:
                print("Connecting...");
                Connect(mBluetoothDevice);
                break;
            case R.id.btn_disconnect:
                print("Disconnect");
                Disconnect( mBluetoothDevice );
                break;
            case R.id.sw:
                Switch sw = (Switch) findViewById(R.id.sw);
                if( sw.isChecked() ) {
                    EnableRemoteListening();
                } else {
                    DisableRemoteListening();
                }
                break;

            case R.id.btn_send:
                print("Start Recording ...");
                StartRecording( 240 );
                break;

            case R.id.btn_exit:
                // remember to disconnect BLE device before exit
                // mBluetoothGatt.disconnect();
                if( mBluetoothGatt!= null ) {
                    mBluetoothGatt.close();
                    mBluetoothGatt = null;
                    mBluetoothManager = null;
                    mBluetoothDevice = null;
                    mBluetoothGattCharacteristicAA71 = null;
                    mBluetoothGattCharacteristicAA72 = null;
                    mBluetoothGattDescriptorsAA71 = null;
                    mBluetoothGattDescriptorsAA72 = null;
                }
                this.finish();
                this.finishAffinity();
                System.exit(0);
                break;
        }
    }

    /**********************************************************************************
     *  @BaichenLi
     *
     *  When the "SCAN" button is clicked, the program starts to Scan for BLE devices,
     *  with the following ScanBLEDevices() function.
     *  Scanning Period is set by the SCAN_PERIOD final value, in milliseconds (ms).
     *  Currently, SCAN_PERIOD is set to 2sec (2000 ms). After 2 seconds, a Runnable
     *  will be run to stop the scanning process.
     *
     *  The BluetoothAdapter.LeScanCallback callback function is and must be defined.
     *  Give any found BLE devices, this callback will be invoked for further processing.
     *
     *  Rules to keep in mind:
     *   1. As soon as you find the desired device, stop scanning.
     *   2. Never scan on a loop, and set a time limit on your scan.
     *      A device that was previously available may have moved out of range,
     *      and continuing to scan drains the battery.
     *  Reference: https://developer.android.com/guide/topics/connectivity/bluetooth-le.html
     **********************************************************************************/
    private void ScanBLEDevices(){
        if( mScanning ) {
            print("Scanning for BLE devices ...");
            return;
        }

        if( mBluetoothGatt!= null ) {
            mBluetoothGatt.close();
        }

        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if( mScanning ) {
                    mScanning = false;
                    mBluetoothAdapter.stopLeScan(mLeScanCallback);
                    print("Stop Scanning!");
                }
            }
        }, SCAN_PERIOD);

        mScanning = true;
        mBluetoothAdapter.startLeScan(mLeScanCallback);
        print("Start Scanning ...");
    }
    // Device scan callback.
    private BluetoothAdapter.LeScanCallback mLeScanCallback = new BluetoothAdapter.LeScanCallback() {
        @Override
        public void onLeScan(final BluetoothDevice device, final int rssi, final byte[] scanRecord) {
            // your implementation here
            print("mLeScanCallback invoked: " + device.getName());
            if( device.getName().toLowerCase().contains("XAmpleBLEPeripheral".toLowerCase()) ) {
                mScanning = false;
                mBluetoothAdapter.stopLeScan(mLeScanCallback);
                mBluetoothDevice = device;
                print("\nAddress:" + device.getAddress(), true);
            }
        }
    };

    /**********************************************************************************
     *  @BaichenLi
     *
     *  Connect to a device, after the scanning process.
     *
     *  1.The first step in interacting with a BLE device is connecting to it
     *      — more specifically, connecting to the GATT server on the device
     *
     *  2.Assuming the connection attempt is successful, the callback
     *      BluetoothGattCallback.onConnectionStateChange() will be called with the newState
     *      argument set to BluetoothProfile.STATE_CONNECTED. After this event, discover services
     *      can be initiated. As the name implies, the goal of the following call is to
     *      determine what services the remote device supports.
     *
     *   When the device responds, you will received the callback
     *   BluetoothGattCallback.onServicesDiscovered(). We must receive this callback
     *   before we can get a list of the supported services.
     *   And once you have a list of services a device supports,
     *   use the following code to get the characteristics for that service.
     *
     **********************************************************************************/
    private void Connect(final BluetoothDevice device) {
        if (device == null) {
            print("No XAmple BLE device has been found.\nPlease press SCAN button first.");
            return;
        }
        // Here, we need a mGattCallback function.
        mBluetoothGatt = device.connectGatt(this, false, mGattCallback);
    }

    private void Disconnect(final BluetoothDevice device) {
        if (device == null) {
            print("No XAmple BLE device has been found.\nPlease press SCAN button first.");
            return;
        }
        // Here, we need a mGattCallback function.
        mBluetoothGatt.close();
    }

    // There are several useful methods in this class, but the following code will only highlight a few necessary ones.
    // Reference: http://toastdroid.com/2014/09/22/android-bluetooth-low-energy-tutorial/
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic) {
            // this will get called anytime you perform a read or write characteristic operation
            byte[] data = characteristic.getValue();
            receivedPacketsCount++;
            print("# of Packets:" + receivedPacketsCount);
        }

        @Override
        public void onConnectionStateChange(final BluetoothGatt gatt, final int status, final int newState) {
            // this will get called when a device connects or disconnects
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                mConnectionState = STATE_CONNECTED;
                print("BLE Device Connected!\nAttempting to start service discovery...");
                mBluetoothGatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                mConnectionState = STATE_DISCONNECTED;
                print("BLE Device Disconnected!");
            }
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt gatt, final int status) {
            // this will get called after the client initiates a BluetoothGatt.discoverServices() call
            if (status == BluetoothGatt.GATT_SUCCESS) {
                print( "\nonServicesDiscovered GATT_SUCCESS", true );
                // Identify Useful Characteristics
                mBluetoothGattCharacteristicAA71 = FindBluetoothGattCharacteristicByUUID("AA71");
                mBluetoothGattCharacteristicAA72 = FindBluetoothGattCharacteristicByUUID("AA72");
                mBluetoothGattDescriptorsAA71 = mBluetoothGattCharacteristicAA71.getDescriptors();
                mBluetoothGattDescriptorsAA72 = mBluetoothGattCharacteristicAA72.getDescriptors();
                for( BluetoothGattDescriptor descriptor : mBluetoothGattDescriptorsAA71 ) {
                    descriptor.setValue( BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE );
                    mBluetoothGatt.writeDescriptor( descriptor );
                }
                for( BluetoothGattDescriptor descriptor : mBluetoothGattDescriptorsAA72 ) {
                    descriptor.setValue( BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE );
                    mBluetoothGatt.writeDescriptor( descriptor );
                }
                //print("\nAA71 Descriptors:"+mBluetoothGattDescriptorsAA71.size(), true);
                // 2: 0x2902, 0x2901
                //print("\nAA72 Descriptors:"+mBluetoothGattDescriptorsAA72.size(), true);
                mBluetoothGattDescriptorCCC = FindBluetoothGattDescriptorByUUID("2902");
                mBluetoothGattDescriptorGeneralData = FindBluetoothGattDescriptorByUUID("2901");
            } else {
                print( "onServicesDiscovered received: " + status );
            }
        }

        @Override
        public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            print( "Read Descriptor: "+ descriptor.getValue() );
        }
        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                print("\nDesc. updated! Value: " + descriptor.getValue(), true);
            } else {
                print("\nDesc. update error! " + status + "Value:" + descriptor.getValue(), true);
            }
            //gatt.readDescriptor(mBluetoothGattDescriptorCCC);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                print("\nCharacteristic updated!", true);
                mBluetoothGatt.readCharacteristic(mBluetoothGattCharacteristicAA72);
                print( "\nAA72 Value: "+characteristic.getValue(), true );
            } else {
                print("\nCharacteristic updated error: " + status, true);
            }
        }
    };

    public BluetoothGattCharacteristic FindBluetoothGattCharacteristicByUUID(String UUID) {
        if( mBluetoothGatt == null ) {
            return null;
        }

        List<BluetoothGattService> services = mBluetoothGatt.getServices();
        for (BluetoothGattService service : services) {
            List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
            // Thus far, we have the basic information: device, supported services,
            // and a list of the characteristics for each service.Each has a UUID.
            // There will be something called Client Configuration Descriptor,
            // and by setting some values, we can enable notifications on the remote device.
            for(BluetoothGattCharacteristic characteristic: characteristics) {
                if( characteristic.getUuid().toString().toLowerCase().contains( UUID.toLowerCase() ) ) {
                    print("\nFound UUID: " + characteristic.getUuid(), true);
                    return characteristic;
                }
                /* Do something to the descriptors as well */
            }
        }
        return null;
    }

    public BluetoothGattDescriptor FindBluetoothGattDescriptorByUUID(String UUID) {
        if( mBluetoothGatt == null ) {
            return null;
        }
        List<BluetoothGattService> services = mBluetoothGatt.getServices();
        for (BluetoothGattService service : services) {
            List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
            for (BluetoothGattCharacteristic characteristic : characteristics) {
                for(BluetoothGattDescriptor descriptor : characteristic.getDescriptors()){
                    if (descriptor.getUuid().toString().toLowerCase().contains(UUID.toLowerCase())) {
                        return descriptor;
                    }
                }
            }
        }
        return null;
    }

    public void EnableRemoteListening() {
        if( mBluetoothGattCharacteristicAA71 == null ) {
            print("Object AA71 Not Found");
            return;
        }
        if( mBluetoothGattDescriptorCCC == null ) {
            print("Object 2902 Not Found");
            return;
        }
        /*
        for(BluetoothGattDescriptor descriptor : mBluetoothGattCharacteristicAA71.getDescriptors()){
            descriptor.setValue( BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE );
            mBluetoothGatt.writeDescriptor(descriptor);
        }
        */
        mBluetoothGatt.setCharacteristicNotification(mBluetoothGattCharacteristicAA71, true);
        mBluetoothGatt.setCharacteristicNotification(mBluetoothGattCharacteristicAA72, true);
    }

    /* we don't need to disable Notification yet.*/
    public void DisableRemoteListening() {
        if( mBluetoothGattCharacteristicAA71 == null ) {
            print("Object AA71 Not Found");
            return;
        }
        if( mBluetoothGattDescriptorCCC == null) {
            print("Object 2902 Not Found");
            return;
        }
        mBluetoothGatt.setCharacteristicNotification(mBluetoothGattCharacteristicAA71, false);
        mBluetoothGatt.setCharacteristicNotification(mBluetoothGattCharacteristicAA72, false);
    }

    public void StartRecording( int number_of_points ) {
        if( mBluetoothGattCharacteristicAA72 == null ) {
            print("Object AA72 Not Found.");
            return;
        }
        byte[] command = new byte[4];
        command[0] = (byte)(number_of_points & 0xff);
        command[1] = (byte)((number_of_points>>8) & 0xff);
        command[2] = 0x02;
        command[3] = 0x30;
        mBluetoothGattCharacteristicAA72.setValue(command);
        if( mBluetoothGatt.writeCharacteristic( mBluetoothGattCharacteristicAA72 ) ) {
            String message = new String("\nWriting 0x");
            for(int index = 0; index < command.length; index++) {
                message += String.format( "%02X", command[index] );
            }
            message += " to Device";
            print( message, true );
        } else {
            print("\nWriting AA72 Init Error.", true);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /***********************************************************************************
     *  @BaichenLi
     *
     *  There is a TextView at the top of the screen, I am using it as a widget to
     *  display some text/info. to the users. Inside the main_activity, the code is
     *  very simple: display.setText( text ).
     *  However, it becomes a little bit tricky when using the BluetoothAdapter.
     *  Somehow, the TextView cannot be accessed through mHandler or mLeScanCallback.
     *  To tackle this problem, the following class is defined, to be used in the
     *  consequent print(String) function, which will be run on the UI thread to
     *  update the TextView widget.
     *
     ***********************************************************************************/
    private class TextPrinter implements Runnable {
        private String text = null;
        private boolean toAppend = false;
        public TextPrinter(String txt, boolean append) {
            text = txt;
            toAppend = append;
        }
        @Override
        public void run() {
            if( toAppend ) {
                display.append( text );
            } else {
                display.setText(text);
            }
        }
    }
    public void print(String text, boolean toAppend) {
        runOnUiThread( new TextPrinter(text, toAppend) );
    }
    public void print(String text) {
        runOnUiThread( new TextPrinter(text, false) );
    }
}
