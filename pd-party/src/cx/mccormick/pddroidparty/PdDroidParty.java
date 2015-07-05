package cx.mccormick.pddroidparty;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.puredata.android.io.AudioParameters;
import org.puredata.android.midi.MidiToPdAdapter;
import org.puredata.android.service.PdService;
import org.puredata.core.PdBase;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.MulticastLock;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.noisepages.nettoyeur.usb.ConnectionFailedException;
import com.noisepages.nettoyeur.usb.DeviceNotConnectedException;
import com.noisepages.nettoyeur.usb.InterfaceNotAvailableException;
import com.noisepages.nettoyeur.usb.UsbBroadcastHandler;
import com.noisepages.nettoyeur.usb.midi.UsbMidiDevice;
import com.noisepages.nettoyeur.usb.midi.UsbMidiDevice.UsbMidiInput;
import com.noisepages.nettoyeur.usb.midi.UsbMidiDevice.UsbMidiOutput;
import com.noisepages.nettoyeur.usb.midi.util.UsbMidiInputSelector;
import com.noisepages.nettoyeur.usb.midi.util.UsbMidiOutputSelector;
import com.noisepages.nettoyeur.usb.util.AsyncDeviceInfoLookup;

import cx.mccormick.pddroidparty.midi.MidiManager;
import cx.mccormick.pddroidparty.pd.PdHelper;
import cx.mccormick.pddroidparty.pd.PdParser;
import cx.mccormick.pddroidparty.pd.PdPatch;
import cx.mccormick.pddroidparty.view.PdDroidPatchView;
import cx.mccormick.pddroidparty.view.PdPartyClockControl;
import cx.mccormick.pddroidparty.widget.LoadSave;
import cx.mccormick.pddroidparty.widget.MenuBang;
import cx.mccormick.pddroidparty.widget.Widget;

public class PdDroidParty extends Activity {
	public PdDroidPatchView patchview = null;
	public static final String INTENT_EXTRA_PATCH_PATH = "PATCH";
	private static final String PD_CLIENT = "PdDroidParty";
	private static final String TAG = "PdDroidParty";
	private static final int SAMPLE_RATE = 44100;
	public static final int DIALOG_NUMBERBOX = 1;
	public static final int DIALOG_SAVE = 2;
	public static final int DIALOG_LOAD = 3;
	
	private MidiManager midiManager;
	
	private PdPartyClockControl clockControl;
	
	private PdPatch patch;
	
	private PdService pdService = null;
	Widget widgetpopped = null;
	MulticastLock wifiMulticastLock = null;
	
	private MenuItem menumidi = null;

	private UsbMidiDevice midiDevice = null;
	private MidiToPdAdapter receiver = new MidiToPdAdapter();
	
	// post a 'toast' alert to the Android UI
	private void post(final String msg) {
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				Toast.makeText(getApplicationContext(), PD_CLIENT + ": " + msg, Toast.LENGTH_LONG).show();
			}
		});
	}
	
	// our connection to the Pd service
	private final ServiceConnection serviceConnection = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			pdService = ((PdService.PdBinder) service).getService();
			initPd();
			midiManager.init(PdDroidParty.this);
			runOnUiThread(new Runnable() {
				public void run() {
					clockControl.initMidiLists();
				}
			});
		}
		
		@Override
		public void onServiceDisconnected(ComponentName name) {
			// this method will never be called
		}
	};
	
	// called when the app is launched
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Intent intent = getIntent();
		String path = intent.getStringExtra(INTENT_EXTRA_PATCH_PATH);
		patch = new PdPatch(path);
		
		midiManager = new MidiManager();
		
		initGui();
		new Thread() {
			@Override
			public void run() {
				bindService(new Intent(PdDroidParty.this, PdService.class), serviceConnection, BIND_AUTO_CREATE);
			}
		}.start();
	}

	// this callback makes sure that we handle orientation changes without audio glitches
	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		initGui();
	}

	// When the app shuts down
	@Override
	protected void onDestroy() {
		super.onDestroy();
		cleanup();
	}
	
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		return true;
	}
	
	// menu launch yeah
	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		menu.clear();
		// add the menu bang menu items
		MenuBang.setMenu(menu);
		// TODO: preferences = ic_menu_preferences
		// midi menu
		// test for platforms that don't support USB OTG devices
		try {
			UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
			if(manager != null)
			{
				menumidi = menu.add(0, Menu.FIRST + menu.size(), 0, "Midi");
				menumidi.setIcon(android.R.drawable.ic_menu_manage); 
			}
		} catch(NoClassDefFoundError e) {
			// don't care
			Log.w(TAG, "USB not available", e);
		}
		
		return super.onPrepareOptionsMenu(menu);
	}
	
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (menumidi != null && item == menumidi) {
			if (midiDevice == null) {
				chooseMidiDevice();
			} else {
				midiDevice.close();
				midiDevice = null;
				post("USB MIDI connection closed");
			}
		} else {
			// pass the menu selection through to the MenuBang manager
			MenuBang.hit(item);
		}
		return super.onOptionsItemSelected(item);
	}
	
	// initialise the GUI with the OpenGL rendering engine
	private void initGui() {
		//setContentView(R.layout.main);
		int flags = WindowManager.LayoutParams.FLAG_FULLSCREEN |
			WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
			WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON; 		
		getWindow().setFlags(flags, flags);
		requestWindowFeature(Window.FEATURE_NO_TITLE);
		
		patchview = new PdDroidPatchView(this, this, patch);
		
		LinearLayout layout = new LinearLayout(this);
		layout.setOrientation(LinearLayout.VERTICAL);
		
		clockControl = new PdPartyClockControl(this, midiManager);
		
		layout.addView(clockControl);
		layout.addView(patchview);
		
		setContentView(layout);
		patchview.requestFocus();
		MenuBang.clear();
	}
	
	// initialise Pd asking for the desired sample rate, parameters, etc.
	private void initPd() {
		Context context = this.getApplicationContext();
		// make sure netreceive can receive broadcast UDP packets
		wifiMulticastLock = ((WifiManager) context.getSystemService(Context.WIFI_SERVICE)).createMulticastLock("PdDroidPartyMulticastLock");
		Log.d(TAG, "Got Multicast Lock (before)? " + wifiMulticastLock.isHeld());
		wifiMulticastLock.acquire();
		Log.d(TAG, "Got Multicast Lock (after)? " + wifiMulticastLock.isHeld());
		// set up the midi stuff
		UsbMidiDevice.installBroadcastHandler(this, new UsbBroadcastHandler() {
			@Override
			public void onPermissionGranted(UsbDevice device) {
				if (midiDevice == null || !midiDevice.matches(device)) return;
				try {
					midiDevice.open(PdDroidParty.this);
				} catch (ConnectionFailedException e) {
					post("USB connection failed");
					midiDevice = null;
					return;
				}
				final UsbMidiOutputSelector outputSelector = new UsbMidiOutputSelector(midiDevice) {

					@Override
					protected void onOutputSelected(UsbMidiOutput output, UsbMidiDevice device, int iface, int index) {
						post("Output selection: Interface " + iface + ", Output " + index);
						try {
							output.getMidiOut();
						} catch (DeviceNotConnectedException e) {
							post("MIDI device has been disconnected");
						} catch (InterfaceNotAvailableException e) {
							post("MIDI interface is unavailable");
						}
					}

					@Override
					protected void onNoSelection(UsbMidiDevice device) {
						post("No output selected");
					}
				};
				new UsbMidiInputSelector(midiDevice) {

					@Override
					protected void onInputSelected(UsbMidiInput input, UsbMidiDevice device, int iface,
							int index) {
						post("Input selection: Interface " + iface + ", Input " + index);
						input.setReceiver(receiver);
						try {
							input.start();
						} catch (DeviceNotConnectedException e) {
							post("MIDI device has been disconnected");
							return;
						} catch (InterfaceNotAvailableException e) {
							post("MIDI interface is unavailable");
							return;
						}
						outputSelector.show(getFragmentManager(), null);
					}

					@Override
					protected void onNoSelection(UsbMidiDevice device) {
						post("No input selected");
						outputSelector.show(getFragmentManager(), null);
					}
				}.show(getFragmentManager(), null);
			}

			@Override
			public void onPermissionDenied(UsbDevice device) {
				if (midiDevice == null || !midiDevice.matches(device)) return;
				post("Permission denied for device " + midiDevice.getCurrentDeviceInfo());
				midiDevice = null;
			}

			@Override
			public void onDeviceDetached(UsbDevice device) {
				if (midiDevice == null || !midiDevice.matches(device)) return;
				midiDevice.close();
				midiDevice = null;
				post("MIDI device disconnected");
			}
		});
		// set a progress dialog running
		final ProgressDialog progress = new ProgressDialog(this);
		progress.setMessage("Loading...");
		progress.setCancelable(false);
		progress.setIndeterminate(true);
		progress.show();
		new Thread() {
			@Override
			public void run() {
				int sRate = AudioParameters.suggestSampleRate();
				Log.d(TAG, "suggested sample rate: " + sRate);
				if (sRate < SAMPLE_RATE) {
					Log.e(TAG, "warning: sample rate is only " + sRate);
				}
				// clamp it
				sRate = Math.min(sRate, SAMPLE_RATE);
				Log.d(TAG, "actual sample rate: " + sRate);
				
				int nIn = Math.min(AudioParameters.suggestInputChannels(), 1);
				Log.d(TAG, "input channels: " + nIn);
				if (nIn == 0) {
					Log.w(TAG, "warning: audio input not available");
				}
				
				int nOut = Math.min(AudioParameters.suggestOutputChannels(), 2);
				Log.d(TAG, "output channels: " + nOut);
				if (nOut == 0) {
					Log.w(TAG, "audio output not available; exiting");
					finish();
					return;
				}
				
				Resources res = getResources();
				
				PdHelper.init();
				
				try {
					// parse the patch for GUI elements
					// p.printAtoms(p.parsePatch(path));
					// get the actual lines of atoms from the patch
					List<String[]> atomlines = PdParser.parsePatch(patch);
					// some devices don't have a mic and might be buggy
					// so don't create the audio in unless we really need it
					// TODO: check a config option for this
					//if (!hasADC(atomlines)) {
					//	nIn = 0;
					//}
					// go ahead and intialise the audio
					try {
						pdService.initAudio(sRate, nIn, nOut, -1);   // negative values default to PdService preferences
					} catch (IOException e) {
						Log.e(TAG, e.toString());
						finish();
					}
					patch.open();
					patchview.buildUI(atomlines);
					// start the audio thread
					String name = res.getString(R.string.app_name);
					pdService.startAudio(new Intent(PdDroidParty.this, PdDroidParty.class), R.drawable.icon, name, "Return to " + name + ".");
					// tell the patch view everything has been loaded
					patchview.loaded();
					// dismiss the progress meter
					progress.dismiss();
				} catch (IOException e) {
					post(e.toString() + "; exiting now");
					finish();
				}
			}
		}.start();
	}
	
	public boolean hasADC(ArrayList<String[]> al) {
		boolean has = false;
		for (String[] line: al) {
			if (line.length >= 5) {
				// find canvas begin and end lines
				if (line[4].equals("adc~")) {
					has = true;
				}
			}
		}
		return has;
	}
	
	// close the app and exit
	@Override
	public void finish() {
		cleanup();
		super.finish();
	}
	
	// quit the Pd service and release other resources
	private void cleanup() {
		// let the screen blank again
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
			}
		});
		// make sure to release all resources
		if (pdService != null) {
			pdService.stopAudio();
		}
		patch.close();
		
		PdBase.sendMessage("pd", "quit", "bang");
		PdBase.release();
		try {
			unbindService(serviceConnection);
		} catch (IllegalArgumentException e) {
			// already unbound
			pdService = null;
		}
		// release midi
		if (midiDevice != null) {
			midiDevice.close();
		}
		UsbMidiDevice.uninstallBroadcastHandler(this);
		// release the lock on wifi multicasting
		if (wifiMulticastLock != null && wifiMulticastLock.isHeld())
			wifiMulticastLock.release();
	}
	
	public void launchDialog(Widget which, int type) {
		widgetpopped = which;
		if (type == DIALOG_NUMBERBOX) {
			Intent it = new Intent(this, NumberboxDialog.class);
			it.putExtra("number", which.getval());
			startActivityForResult(it, DIALOG_NUMBERBOX);
		} else if (type == DIALOG_SAVE) {
			Intent it = new Intent(this, SaveDialog.class);
			it.putExtra("filename", ((LoadSave)which).getFilename());
			it.putExtra("directory", ((LoadSave)which).getDirectory());
			it.putExtra("extension", ((LoadSave)which).getExtension());
			startActivityForResult(it, DIALOG_SAVE);
		} else if (type == DIALOG_LOAD) {
			Intent it = new Intent(this, LoadDialog.class);
			it.putExtra("filename", ((LoadSave)which).getFilename());
			it.putExtra("directory", patch.getFile(((LoadSave)which).getDirectory()).getPath());
			it.putExtra("extension", ((LoadSave)which).getExtension());
			startActivityForResult(it, DIALOG_LOAD);
		}
	}
	
	private void chooseMidiDevice() {
		// set a progress dialog running
		final ProgressDialog progress = new ProgressDialog(this);
		progress.setMessage("Waiting for USB midi");
		progress.setCancelable(false);
		progress.setIndeterminate(true);
		progress.show();
		final List<UsbMidiDevice> devices = UsbMidiDevice.getMidiDevices(this);
		new AsyncDeviceInfoLookup() {
			@Override
			protected void onLookupComplete() {
				// ok we are done
				progress.dismiss();
				if (!devices.isEmpty()) {
					String devicenames[] = new String[devices.size()];
					// loop through the devices and get their names
					for (int i = 0; i < devices.size(); ++i) {
						devicenames[i] = devices.get(i).getCurrentDeviceInfo().toString();
					}
					// construct the alert we will show
					new AlertDialog.Builder(PdDroidParty.this)
					// make the alert and show it
					.setTitle("Midi device")
					.setItems(devicenames, new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int which) {
							// The 'which' argument contains the index position
							// of the selected item
							midiDevice = devices.get(which);
							midiDevice.requestPermission(PdDroidParty.this);
						}
					})
					.show();
				} else {
					post("No midi devices found.");
				}

				/*new UsbDeviceSelector<UsbMidiDevice>(devices) {
					@Override
					protected void onDeviceSelected(UsbMidiDevice device) {
						midiDevice = device;
						midiDevice.requestPermission(PdDroidParty.this);
					}

					@Override
					protected void onNoSelection() {
						post("No device selected");
					}
				}.show(getSupportFragmentManager(), null);*/
			}
		}.execute(devices.toArray(new UsbMidiDevice[devices.size()]));
	}
	
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data); 
		if(requestCode == PdPartyClockControl.SETUP_ACTIVITY_CODE)
		{
			clockControl.initMidiLists();
		}
		else if (resultCode == RESULT_OK) {
			if (widgetpopped != null) {
				if (requestCode == DIALOG_NUMBERBOX) {
					widgetpopped.receiveFloat(data.getFloatExtra("number", 0));
					widgetpopped.send("" + widgetpopped.getval());
				} else if (requestCode == DIALOG_SAVE) {
					((LoadSave)widgetpopped).gotFilename("save", data.getStringExtra("filename"));
				} else if (requestCode == DIALOG_LOAD) {
					((LoadSave)widgetpopped).gotFilename("load", data.getStringExtra("filename"));
				}
				// we're done with our originating widget and dialog
				widgetpopped = null;
				// force a redraw
				patchview.invalidate();
			}
		}
	}

}
