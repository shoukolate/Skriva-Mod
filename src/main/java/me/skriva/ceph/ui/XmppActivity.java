package me.skriva.ceph.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.BoolRes;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AlertDialog.Builder;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.databinding.DataBindingUtil;

import com.github.piasy.biv.BigImageViewer;
import com.github.piasy.biv.loader.fresco.FrescoImageLoader;
import com.vanniktech.emoji.EmojiManager;
import com.vanniktech.emoji.google.GoogleEmojiProvider;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import me.skriva.ceph.Config;
import me.skriva.ceph.R;
import me.skriva.ceph.databinding.DialogQuickeditBinding;
import me.skriva.ceph.entities.Account;
import me.skriva.ceph.entities.Contact;
import me.skriva.ceph.entities.Conversation;
import me.skriva.ceph.entities.Message;
import me.skriva.ceph.entities.Presences;
import me.skriva.ceph.services.AvatarService;
import me.skriva.ceph.services.BarcodeProvider;
import me.skriva.ceph.services.XmppConnectionService;
import me.skriva.ceph.services.XmppConnectionService.XmppConnectionBinder;
import me.skriva.ceph.ui.service.EmojiService;
import me.skriva.ceph.ui.util.MenuDoubleTabUtil;
import me.skriva.ceph.ui.util.PresenceSelector;
import me.skriva.ceph.ui.util.SoftKeyboardUtils;
import me.skriva.ceph.utils.AccountUtils;
import me.skriva.ceph.utils.ExceptionHelper;
import me.skriva.ceph.utils.ThemeHelper;
import me.skriva.ceph.xmpp.OnKeyStatusUpdated;
import me.skriva.ceph.xmpp.OnUpdateBlocklist;
import rocks.xmpp.addr.Jid;

public abstract class XmppActivity extends ActionBarActivity {

	public static final String EXTRA_ACCOUNT = "account";
	static final int REQUEST_INVITE_TO_CONVERSATION = 0x0102;
	static final int REQUEST_BATTERY_OP = 0x49ff;

	private static final int BITMAP_SCALE = 260;
	private static final int BITMAP_SCALE_FOR_QUOTED_IMAGE = 60;

	public XmppConnectionService xmppConnectionService;
	boolean xmppConnectionServiceBound = false;

	static final String FRAGMENT_TAG_DIALOG = "dialog";

	private boolean isCameraFeatureAvailable = false;

	int mTheme;
	protected boolean mUsingEnterKey = false;
	Toast mToast;
	ConferenceInvite mPendingConferenceInvite = null;
	private final ServiceConnection mConnection = new ServiceConnection() {

		@Override
		public void onServiceConnected(ComponentName className, IBinder service) {
			XmppConnectionBinder binder = (XmppConnectionBinder) service;
			xmppConnectionService = binder.getService();
			xmppConnectionServiceBound = true;
			registerListeners();
			onBackendConnected();
		}

		@Override
		public void onServiceDisconnected(ComponentName arg0) {
			xmppConnectionServiceBound = false;
		}
	};
	private DisplayMetrics metrics;
	private long mLastUiRefresh = 0;
	private final Handler mRefreshUiHandler = new Handler();
	private final Runnable mRefreshUiRunnable = () -> {
		mLastUiRefresh = SystemClock.elapsedRealtime();
		refreshUiReal();
	};
	private final UiCallback<Conversation> adhocCallback = new UiCallback<Conversation>() {
		@Override
		public void success(final Conversation conversation) {
			runOnUiThread(() -> {
				switchToConversation(conversation);
				hideToast();
			});
		}

		@Override
		public void error(final int errorCode, Conversation object) {
			runOnUiThread(() -> replaceToast(getString(errorCode)));
		}

		@Override
		public void userInputRequired(PendingIntent pi, Conversation object) {

		}
	};
	boolean mSkipBackgroundBinding = false;

	private static boolean cancelPotentialWork(Message message, ImageView imageView) {
		final BitmapWorkerTask bitmapWorkerTask = getBitmapWorkerTask(imageView);

		if (bitmapWorkerTask != null) {
			final Message oldMessage = bitmapWorkerTask.message;
			if (oldMessage == null || message != oldMessage) {
				bitmapWorkerTask.cancel(true);
			} else {
				return false;
			}
		}
		return true;
	}

	private static BitmapWorkerTask getBitmapWorkerTask(ImageView imageView) {
		if (imageView != null) {
			final Drawable drawable = imageView.getDrawable();
			if (drawable instanceof AsyncDrawable) {
				final AsyncDrawable asyncDrawable = (AsyncDrawable) drawable;
				return asyncDrawable.getBitmapWorkerTask();
			}
		}
		return null;
	}

	void hideToast() {
		if (mToast != null) {
			mToast.cancel();
		}
	}

	void replaceToast(String msg) {
		replaceToast(msg, true);
	}

	void replaceToast(String msg, boolean showlong) {
		hideToast();
		mToast = Toast.makeText(this, msg, showlong ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
		mToast.show();
	}

	final void refreshUi() {
		final long diff = SystemClock.elapsedRealtime() - mLastUiRefresh;
		if (diff > Config.REFRESH_UI_INTERVAL) {
			mRefreshUiHandler.removeCallbacks(mRefreshUiRunnable);
			runOnUiThread(mRefreshUiRunnable);
		} else {
			final long next = Config.REFRESH_UI_INTERVAL - diff;
			mRefreshUiHandler.removeCallbacks(mRefreshUiRunnable);
			mRefreshUiHandler.postDelayed(mRefreshUiRunnable, next);
		}
	}

	abstract protected void refreshUiReal();

	@Override
	protected void onStart() {
		super.onStart();
		if (!xmppConnectionServiceBound) {
			if (this.mSkipBackgroundBinding) {
				Log.d(Config.LOGTAG,"skipping background binding");
			} else {
				connectToBackend();
			}
		} else {
			this.registerListeners();
			this.onBackendConnected();
		}
	}

	private void connectToBackend() {
		Intent intent = new Intent(this, XmppConnectionService.class);
		intent.setAction("ui");
		try {
			startService(intent);
		} catch (IllegalStateException e) {
			Log.w(Config.LOGTAG,"unable to start service from "+getClass().getSimpleName());
		}
		bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
	}

	@Override
	protected void onStop() {
		super.onStop();
		if (xmppConnectionServiceBound) {
			this.unregisterListeners();
			unbindService(mConnection);
			xmppConnectionServiceBound = false;
		}
	}

	abstract void onBackendConnected();

	private void registerListeners() {
		if (this instanceof XmppConnectionService.OnConversationUpdate) {
			this.xmppConnectionService.setOnConversationListChangedListener((XmppConnectionService.OnConversationUpdate) this);
		}
		if (this instanceof XmppConnectionService.OnAccountUpdate) {
			this.xmppConnectionService.setOnAccountListChangedListener((XmppConnectionService.OnAccountUpdate) this);
		}
		if (this instanceof XmppConnectionService.OnCaptchaRequested) {
			this.xmppConnectionService.setOnCaptchaRequestedListener((XmppConnectionService.OnCaptchaRequested) this);
		}
		if (this instanceof XmppConnectionService.OnRosterUpdate) {
			this.xmppConnectionService.setOnRosterUpdateListener((XmppConnectionService.OnRosterUpdate) this);
		}
		if (this instanceof XmppConnectionService.OnMucRosterUpdate) {
			this.xmppConnectionService.setOnMucRosterUpdateListener((XmppConnectionService.OnMucRosterUpdate) this);
		}
		if (this instanceof OnUpdateBlocklist) {
			this.xmppConnectionService.setOnUpdateBlocklistListener((OnUpdateBlocklist) this);
		}
		if (this instanceof XmppConnectionService.OnShowErrorToast) {
			this.xmppConnectionService.setOnShowErrorToastListener((XmppConnectionService.OnShowErrorToast) this);
		}
		if (this instanceof OnKeyStatusUpdated) {
			this.xmppConnectionService.setOnKeyStatusUpdatedListener((OnKeyStatusUpdated) this);
		}
	}

	private void unregisterListeners() {
		if (this instanceof XmppConnectionService.OnConversationUpdate) {
			this.xmppConnectionService.removeOnConversationListChangedListener((XmppConnectionService.OnConversationUpdate) this);
		}
		if (this instanceof XmppConnectionService.OnAccountUpdate) {
			this.xmppConnectionService.removeOnAccountListChangedListener((XmppConnectionService.OnAccountUpdate) this);
		}
		if (this instanceof XmppConnectionService.OnCaptchaRequested) {
			this.xmppConnectionService.removeOnCaptchaRequestedListener((XmppConnectionService.OnCaptchaRequested) this);
		}
		if (this instanceof XmppConnectionService.OnRosterUpdate) {
			this.xmppConnectionService.removeOnRosterUpdateListener((XmppConnectionService.OnRosterUpdate) this);
		}
		if (this instanceof XmppConnectionService.OnMucRosterUpdate) {
			this.xmppConnectionService.removeOnMucRosterUpdateListener((XmppConnectionService.OnMucRosterUpdate) this);
		}
		if (this instanceof OnUpdateBlocklist) {
			this.xmppConnectionService.removeOnUpdateBlocklistListener((OnUpdateBlocklist) this);
		}
		if (this instanceof XmppConnectionService.OnShowErrorToast) {
			this.xmppConnectionService.removeOnShowErrorToastListener((XmppConnectionService.OnShowErrorToast) this);
		}
		if (this instanceof OnKeyStatusUpdated) {
			this.xmppConnectionService.removeOnNewKeysAvailableListener((OnKeyStatusUpdated) this);
		}
	}

	@Override
	public boolean onOptionsItemSelected(final MenuItem item) {
		switch (item.getItemId()) {
			case R.id.action_settings:
				startActivity(new Intent(this, SettingsActivity.class));
				break;
			case R.id.action_accounts:
				AccountUtils.launchManageAccounts(this);
				break;
			case R.id.action_account:
				AccountUtils.launchManageAccount(this);
				break;
			case android.R.id.home:
				finish();
				break;
			case R.id.action_show_qr_code:
				showQrCode();
				break;
		}
		return super.onOptionsItemSelected(item);
	}

	public void selectPresence(final Conversation conversation, final PresenceSelector.OnPresenceSelected listener) {
		final Contact contact = conversation.getContact();
		if (!contact.showInRoster()) {
			showAddToRosterDialog(conversation.getContact());
		} else {
			final Presences presences = contact.getPresences();
			if (presences.size() == 0) {
				if (!contact.getOption(Contact.Options.TO)
						&& !contact.getOption(Contact.Options.ASKING)
						&& contact.getAccount().getStatus() == Account.State.ONLINE) {
					showAskForPresenceDialog(contact);
				} else if (!contact.getOption(Contact.Options.TO)
						|| !contact.getOption(Contact.Options.FROM)) {
					PresenceSelector.warnMutualPresenceSubscription(this, conversation, listener);
				} else {
					conversation.setNextCounterpart(null);
					listener.onPresenceSelected();
				}
			} else if (presences.size() == 1) {
				String presence = presences.toResourceArray()[0];
				try {
					conversation.setNextCounterpart(Jid.of(contact.getJid().getLocal(), contact.getJid().getDomain(), presence));
				} catch (IllegalArgumentException e) {
					conversation.setNextCounterpart(null);
				}
				listener.onPresenceSelected();
			} else {
				PresenceSelector.showPresenceSelectionDialog(this, conversation, listener);
			}
		}
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		metrics = getResources().getDisplayMetrics();
		ExceptionHelper.init(getApplicationContext());

		// Load Emojis
		new EmojiService(this).init();
		EmojiManager.install(new GoogleEmojiProvider());

		//BigImageViewer
		BigImageViewer.initialize(FrescoImageLoader.with(getApplicationContext()));
		this.isCameraFeatureAvailable = getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA);

		this.mTheme = findTheme();
		setTheme(this.mTheme);

		this.mUsingEnterKey = usingEnterKey();
	}

	boolean isCameraFeatureAvailable() {
		return this.isCameraFeatureAvailable;
	}

	public boolean isDarkTheme() {
		return ThemeHelper.isDark(mTheme);
	}

	public int getThemeResource(int r_attr_name, int r_drawable_def) {
		int[] attrs = {r_attr_name};
		TypedArray ta = this.getTheme().obtainStyledAttributes(attrs);

		int res = ta.getResourceId(0, r_drawable_def);
		ta.recycle();

		return res;
	}

	boolean isOptimizingBattery() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			final PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
			return pm != null
					&& !pm.isIgnoringBatteryOptimizations(getPackageName());
		} else {
			return false;
		}
	}

	boolean isAffectedByDataSaver() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			final ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
			return cm != null
					&& cm.isActiveNetworkMetered()
					&& cm.getRestrictBackgroundStatus() == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED;
		} else {
			return false;
		}
	}

	private boolean usingEnterKey() {
		return getBooleanPreference("display_enter_key", R.bool.display_enter_key);
	}

	SharedPreferences getPreferences() {
		return PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
	}

	boolean getBooleanPreference(String name, @BoolRes int res) {
		return getPreferences().getBoolean(name, getResources().getBoolean(res));
	}

	public void switchToConversation(Conversation conversation) {
		switchToConversation(conversation, null);
	}

	void switchToConversationAndCommentMessage(Conversation conversation, String messageReference, boolean quoteMessage) {
		switchToConversation(conversation, null, messageReference, quoteMessage, null, false, false);
	}

	void switchToConversation(Conversation conversation, String text) {
		switchToConversation(conversation, text, null, false, null, false, false);
	}

	void switchToConversationDoNotAppend(Conversation conversation, String text) {
		switchToConversation(conversation, text, null , false, null, false, true);
	}

	public void highlightInMuc(Conversation conversation, String nick) {
		switchToConversation(conversation, null, null , false, nick, false, false);
	}

	public void privateMsgInMuc(Conversation conversation, String nick) {
		switchToConversation(conversation, null, null , false, nick, true, false);
	}

	private void switchToConversation(Conversation conversation, String text, String messageReference, boolean quoteMessage, String nick, boolean pm, boolean doNotAppend) {
		Intent intent = new Intent(this, ConversationsActivity.class);
		intent.setAction(ConversationsActivity.ACTION_VIEW_CONVERSATION);
		intent.putExtra(ConversationsActivity.EXTRA_CONVERSATION, conversation.getUuid());
		if (text != null) {
			intent.putExtra(Intent.EXTRA_TEXT, text);
			if (messageReference != null) {
				intent.putExtra(ConversationsActivity.EXTRA_MESSAGE_REFERENCE, messageReference);
				if (quoteMessage) {
					intent.putExtra(ConversationsActivity.EXTRA_QUOTE_MESSAGE, true);
				}
			}
		}
		if (nick != null) {
			intent.putExtra(ConversationsActivity.EXTRA_NICK, nick);
			intent.putExtra(ConversationsActivity.EXTRA_IS_PRIVATE_MESSAGE, pm);
		}
		if (doNotAppend) {
			intent.putExtra(ConversationsActivity.EXTRA_DO_NOT_APPEND, true);
		}
		intent.setFlags(intent.getFlags() | Intent.FLAG_ACTIVITY_CLEAR_TOP);
		startActivity(intent);
		finish();
	}

	public void switchToContactDetails(Contact contact) {
		switchToContactDetails(contact, null);
	}

	public void switchToContactDetails(Contact contact, String messageFingerprint) {
		Intent intent = new Intent(this, ContactDetailsActivity.class);
		intent.setAction(ContactDetailsActivity.ACTION_VIEW_CONTACT);
		intent.putExtra(EXTRA_ACCOUNT, contact.getAccount().getJid().asBareJid().toString());
		intent.putExtra("contact", contact.getJid().toString());
		intent.putExtra("fingerprint", messageFingerprint);
		startActivity(intent);
	}

	public void switchToAccount(Account account, String fingerprint) {
		switchToAccount(account, false, fingerprint);
	}

	public void switchToAccount(Account account) {
		switchToAccount(account, false, null);
	}

	private void switchToAccount(Account account, boolean init, String fingerprint) {
		Intent intent = new Intent(this, EditAccountActivity.class);
		intent.putExtra("jid", account.getJid().asBareJid().toString());
		intent.putExtra("init", init);
		if (init) {
			intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
		}
		if (fingerprint != null) {
			intent.putExtra("fingerprint", fingerprint);
		}
		startActivity(intent);
		if (init) {
			overridePendingTransition(0, 0);
		}
	}

	void delegateUriPermissionsToService(Uri uri) {
		Intent intent = new Intent(this, XmppConnectionService.class);
		intent.setAction(Intent.ACTION_SEND);
		intent.setData(uri);
		intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		try {
			startService(intent);
		} catch (Exception e) {
			Log.e(Config.LOGTAG,"unable to delegate uri permission",e);
		}
	}

	void inviteToConversation(Conversation conversation) {
		startActivityForResult(ChooseContactActivity.create(this,conversation), REQUEST_INVITE_TO_CONVERSATION);
	}

	@SuppressWarnings("deprecation")
	@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
	protected void setListItemBackgroundOnView(View view) {
		int sdk = android.os.Build.VERSION.SDK_INT;
		if (sdk < android.os.Build.VERSION_CODES.JELLY_BEAN) {
			view.setBackgroundDrawable(getResources().getDrawable(R.drawable.greybackground));
		} else {
			view.setBackground(getResources().getDrawable(R.drawable.greybackground));
		}
	}

	protected void displayErrorDialog(final int errorCode) {
		runOnUiThread(() -> {
			Builder builder = new Builder(XmppActivity.this);
			builder.setIconAttribute(android.R.attr.alertDialogIcon);
			builder.setTitle(getString(R.string.error));
			builder.setMessage(errorCode);
			builder.setNeutralButton(R.string.accept, null);
			builder.create().show();
		});

	}

	void showAddToRosterDialog(final Contact contact) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setTitle(contact.getJid().toString());
		builder.setMessage(getString(R.string.not_in_roster));
		builder.setNegativeButton(getString(R.string.cancel), null);
		builder.setPositiveButton(getString(R.string.add_contact), (dialog, which) -> xmppConnectionService.createContact(contact,true));
		builder.create().show();
	}

	private void showAskForPresenceDialog(final Contact contact) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setTitle(contact.getJid().toString());
		builder.setMessage(R.string.request_presence_updates);
		builder.setNegativeButton(R.string.cancel, null);
		builder.setPositiveButton(R.string.request_now,
				(dialog, which) -> {
					if (xmppConnectionServiceBound) {
						xmppConnectionService.sendPresencePacket(contact
								.getAccount(), xmppConnectionService
								.getPresenceGenerator()
								.requestPresenceUpdatesFrom(contact));
					}
				});
		builder.create().show();
	}

	void quickEdit(String previousValue, OnValueEdited callback) {
		quickEdit(previousValue, callback, R.string.nickname, false, false);
	}

	void quickEdit(String previousValue, @StringRes int hint, OnValueEdited callback) {
		quickEdit(previousValue, callback, hint, false, true);
	}

	void quickPasswordEdit(String previousValue, OnValueEdited callback) {
		quickEdit(previousValue, callback, R.string.password, true, false);
	}

	@SuppressLint("InflateParams")
	private void quickEdit(final String previousValue,
	                       final OnValueEdited callback,
	                       final @StringRes int hint,
	                       boolean password,
	                       boolean permitEmpty) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		DialogQuickeditBinding binding = DataBindingUtil.inflate(getLayoutInflater(),R.layout.dialog_quickedit, null, false);
		if (password) {
			binding.inputEditText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
		}
		builder.setPositiveButton(R.string.accept, null);
		if (hint != 0) {
			binding.inputLayout.setHint(getString(hint));
		}
		binding.inputEditText.requestFocus();
		if (previousValue != null) {
			binding.inputEditText.getText().append(previousValue);
		}
		builder.setView(binding.getRoot());
		builder.setNegativeButton(R.string.cancel, null);
		final AlertDialog dialog = builder.create();
		dialog.setOnShowListener(d -> SoftKeyboardUtils.showKeyboard(binding.inputEditText));
		dialog.show();
		View.OnClickListener clickListener = v -> {
			String value = binding.inputEditText.getText().toString();
			if (!value.equals(previousValue) && (!value.trim().isEmpty() || permitEmpty)) {
				String error = callback.onValueEdited(value);
				if (error != null) {
					binding.inputLayout.setError(error);
					return;
				}
			}
			SoftKeyboardUtils.hideSoftKeyboard(binding.inputEditText);
			dialog.dismiss();
		};
		dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(clickListener);
		dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener((v -> {
			SoftKeyboardUtils.hideSoftKeyboard(binding.inputEditText);
			dialog.dismiss();
		}));
		dialog.setCanceledOnTouchOutside(false);
		dialog.setOnDismissListener(dialog1 -> SoftKeyboardUtils.hideSoftKeyboard(binding.inputEditText));
	}

	boolean hasStoragePermission(int requestCode) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
				requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, requestCode);
				return false;
			} else {
				return true;
			}
		} else {
			return true;
		}
	}

	protected void onActivityResult(int requestCode, int resultCode, final Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode == REQUEST_INVITE_TO_CONVERSATION && resultCode == RESULT_OK) {
			mPendingConferenceInvite = ConferenceInvite.parse(data);
			if (xmppConnectionServiceBound && mPendingConferenceInvite != null) {
				if (mPendingConferenceInvite.execute(this)) {
					mToast = Toast.makeText(this, R.string.creating_conference, Toast.LENGTH_LONG);
					mToast.show();
				}
				mPendingConferenceInvite = null;
			}
		}
	}

	public boolean copyTextToClipboard(String text, int labelResId) {
		ClipboardManager mClipBoardManager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
		String label = getResources().getString(labelResId);
		if (mClipBoardManager != null) {
			ClipData mClipData = ClipData.newPlainText(label, text);
			mClipBoardManager.setPrimaryClip(mClipData);
			return true;
		}
		return false;
	}

	protected boolean manuallyChangePresence() {
		return getBooleanPreference(SettingsActivity.MANUALLY_CHANGE_PRESENCE, R.bool.manually_change_presence);
	}

	private String getShareableUri() {
		return getShareableUri(false);
	}

	String getShareableUri(boolean http) {
		return null;
	}

	void shareLink(boolean http) {
		String uri = getShareableUri(http);
		if (uri == null || uri.isEmpty()) {
			return;
		}
		Intent intent = new Intent(Intent.ACTION_SEND);
		intent.setType("text/plain");
		intent.putExtra(Intent.EXTRA_TEXT, getShareableUri(http));
		try {
			startActivity(Intent.createChooser(intent, getText(R.string.share_uri_with)));
		} catch (ActivityNotFoundException e) {
			Toast.makeText(this, R.string.no_application_to_share_uri, Toast.LENGTH_SHORT).show();
		}
	}

	@Override
	public void onResume() {
		super.onResume();
	}

	int findTheme() {
		return ThemeHelper.find(this);
	}

	@Override
	public void onPause() {
		super.onPause();
	}

	@Override
	public boolean onMenuOpened(int id, Menu menu) {
		if(id == AppCompatDelegate.FEATURE_SUPPORT_ACTION_BAR && menu != null) {
			MenuDoubleTabUtil.recordMenuOpen();
		}
		return super.onMenuOpened(id, menu);
	}

	private void showQrCode() {
		showQrCode(getShareableUri());
	}

	void showQrCode(final String uri) {
		if (uri == null || uri.isEmpty()) {
			return;
		}
		Point size = new Point();
		getWindowManager().getDefaultDisplay().getSize(size);
		final int width = (size.x < size.y ? size.x : size.y);
		Bitmap bitmap = BarcodeProvider.create2dBarcodeBitmap(uri, width);
		ImageView view = new ImageView(this);
		view.setBackgroundColor(Color.WHITE);
		view.setImageBitmap(bitmap);
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setView(view);
		builder.create().show();
	}

	Account extractAccount(Intent intent) {
		String jid = intent != null ? intent.getStringExtra(EXTRA_ACCOUNT) : null;
		try {
			return jid != null ? xmppConnectionService.findAccountByJid(Jid.of(jid)) : null;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	public AvatarService avatarService() {
		return xmppConnectionService.getAvatarService();
	}

	public void loadBitmap(Message message, ImageView imageView) {
		loadBitmap(message, imageView, BITMAP_SCALE, false);
	}

	public void loadBitmapForReferencedImageMessage(Message referencedMessage, ImageView imageView) {
		loadBitmap(referencedMessage, imageView, BITMAP_SCALE_FOR_QUOTED_IMAGE, false);
	}

	private void loadBitmap(Message message, ImageView imageView, int scale, boolean cacheOnly) {
		Bitmap bm;
		try {
			bm = xmppConnectionService.getFileBackend().getThumbnail(message, (int) (metrics.density * scale), cacheOnly);
		} catch (IOException e) {
			bm = null;
		}
		if (bm != null) {
			cancelPotentialWork(message, imageView);
			imageView.setImageBitmap(bm);
			imageView.setBackgroundColor(0x00000000);
		} else {
			if (cancelPotentialWork(message, imageView)) {
				imageView.setBackgroundColor(0xff333333);
				imageView.setImageDrawable(null);
				final BitmapWorkerTask task = new BitmapWorkerTask(imageView);
				final AsyncDrawable asyncDrawable = new AsyncDrawable(
						getResources(), task);
				imageView.setImageDrawable(asyncDrawable);
				try {
					task.execute(message);
				} catch (final RejectedExecutionException ignored) {
					ignored.printStackTrace();
				}
			}
		}
	}

	interface OnValueEdited {
		String onValueEdited(String value);
	}

	public static class ConferenceInvite {
		private String uuid;
		private final List<Jid> jids = new ArrayList<>();

		public static ConferenceInvite parse(Intent data) {
			ConferenceInvite invite = new ConferenceInvite();
			invite.uuid = data.getStringExtra(ChooseContactActivity.EXTRA_CONVERSATION);
			if (invite.uuid == null) {
				return null;
			}
			invite.jids.addAll(ChooseContactActivity.extractJabberIds(data));
			return invite;
		}

		public boolean execute(XmppActivity activity) {
			XmppConnectionService service = activity.xmppConnectionService;
			Conversation conversation = service.findConversationByUuid(this.uuid);
			if (conversation == null) {
				return false;
			}
			if (conversation.getMode() == Conversation.MODE_MULTI) {
				for (Jid jid : jids) {
					service.invite(conversation, jid);
				}
				return false;
			} else {
				jids.add(conversation.getJid().asBareJid());
				return service.createAdhocConference(conversation.getAccount(), null, jids, activity.adhocCallback);
			}
		}
	}

	static class BitmapWorkerTask extends AsyncTask<Message, Void, Bitmap> {
		private final WeakReference<ImageView> imageViewReference;
		private Message message = null;

		private BitmapWorkerTask(ImageView imageView) {
			this.imageViewReference = new WeakReference<>(imageView);
		}

		@Override
		protected Bitmap doInBackground(Message... params) {
			if (isCancelled()) {
				return null;
			}
			message = params[0];
			try {
				final XmppActivity activity = find(imageViewReference);
				if (activity != null && activity.xmppConnectionService != null) {
					return activity.xmppConnectionService.getFileBackend().getThumbnail(message, (int) (activity.metrics.density * 288), false);
				} else {
					return null;
				}
			} catch (IOException e) {
				return null;
			}
		}

		@Override
		protected void onPostExecute(final Bitmap bitmap) {
			if (!isCancelled()) {
				final ImageView imageView = imageViewReference.get();
				if (imageView != null) {
					imageView.setImageBitmap(bitmap);
					imageView.setBackgroundColor(bitmap == null ? 0xff333333 : 0x00000000);
				}
			}
		}
	}

	private static class AsyncDrawable extends BitmapDrawable {
		private final WeakReference<BitmapWorkerTask> bitmapWorkerTaskReference;

		private AsyncDrawable(Resources res, BitmapWorkerTask bitmapWorkerTask) {
			super(res, (Bitmap) null);
			bitmapWorkerTaskReference = new WeakReference<>(bitmapWorkerTask);
		}

		private BitmapWorkerTask getBitmapWorkerTask() {
			return bitmapWorkerTaskReference.get();
		}
	}

	public static XmppActivity find(@NonNull  WeakReference<ImageView> viewWeakReference) {
		final View view = viewWeakReference.get();
		return view == null ? null : find(view);
	}

	public static XmppActivity find(@NonNull final View view) {
		Context context = view.getContext();
		while (context instanceof ContextWrapper) {
			if (context instanceof XmppActivity) {
				return (XmppActivity) context;
			}
			context = ((ContextWrapper)context).getBaseContext();
		}
		return null;
	}
}
