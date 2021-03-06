package it.feio.android.omninotes.async;

import it.feio.android.omninotes.MainActivity;
import it.feio.android.omninotes.R;
import it.feio.android.omninotes.db.DbHelper;
import it.feio.android.omninotes.models.Attachment;
import it.feio.android.omninotes.models.Category;
import it.feio.android.omninotes.models.Note;
import it.feio.android.omninotes.models.listeners.OnAttachingFileListener;
import it.feio.android.omninotes.utils.Constants;
import it.feio.android.omninotes.utils.GeocodeHelper;
import it.feio.android.omninotes.utils.NotificationsHelper;
import it.feio.android.omninotes.utils.StorageManager;
import it.feio.android.omninotes.utils.TextHelper;
import it.feio.android.springpadimporter.Importer;
import it.feio.android.springpadimporter.models.SpringpadAttachment;
import it.feio.android.springpadimporter.models.SpringpadComment;
import it.feio.android.springpadimporter.models.SpringpadElement;
import it.feio.android.springpadimporter.models.SpringpadItem;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import listeners.ZipProgressesListener;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;
import android.app.IntentService;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.text.Html;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;
import exceptions.ImportException;

public class DataBackupIntentService extends IntentService implements OnAttachingFileListener {

	public final static String INTENT_BACKUP_NAME = "backup_name";
	public final static String INTENT_BACKUP_INCLUDE_SETTINGS = "backup_include_settings";
	public final static String ACTION_DATA_EXPORT = "action_data_export";
	public final static String ACTION_DATA_IMPORT = "action_data_import";
	public final static String ACTION_DATA_IMPORT_SPRINGPAD = "action_data_import_springpad";
	public final static String ACTION_DATA_DELETE = "action_data_delete";
	public final static String EXTRA_SPRINGPAD_BACKUP = "extra_springpad_backup";

	private SharedPreferences prefs;
	private NotificationsHelper mNotificationsHelper;
	
	private int importedSpringpadNotes, importedSpringpadNotebooks;


	public DataBackupIntentService() {
		super("DataBackupIntentService");
	}


	@Override
	protected void onHandleIntent(Intent intent) {
		// PowerManager pm = (PowerManager)
		// getSystemService(Context.POWER_SERVICE);
		// PowerManager.WakeLock wl =
		// pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
		// // Acquire the lock
		// wl.acquire();

		prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_MULTI_PROCESS);

		// Creates an indeterminate processing notification until the work is complete
		mNotificationsHelper = new NotificationsHelper(this)
				.createNotification(R.drawable.ic_stat_notification_icon, getString(R.string.working), null)
				.setIndeterminate().setOngoing().show();

		// If an alarm has been fired a notification must be generated
		if (ACTION_DATA_EXPORT.equals(intent.getAction())) {
			exportData(intent);
		} else if (ACTION_DATA_IMPORT.equals(intent.getAction())) {
			importData(intent);
		} else if (ACTION_DATA_IMPORT_SPRINGPAD.equals(intent.getAction())) {
			importDataFromSpringpad(intent);
		} else if (ACTION_DATA_DELETE.equals(intent.getAction())) {
			deleteData(intent);
		}

		// Release the lock
		// Log.d(TAG, "Releasing power lock, all done");
		// wl.release();

	}


	synchronized private void exportData(Intent intent) {

		// Gets backup folder
		String backupName = intent.getStringExtra(INTENT_BACKUP_NAME);
		File backupDir = StorageManager.getBackupDir(backupName);

		// Directory clean in case of previously used backup name
		StorageManager.delete(this, backupDir.getAbsolutePath());

		// Directory is re-created in case of previously used backup name (removed above)
		backupDir = StorageManager.getBackupDir(backupName);

		// Database backup
		exportDB(backupDir);

		// Attachments backup
		exportAttachments(backupDir);

		// Settings
		if (intent.getBooleanExtra(INTENT_BACKUP_INCLUDE_SETTINGS, true)) ;
		exportSettings(backupDir);

		// Notification of operation ended
		String title = getString(R.string.data_export_completed);
		String text = backupDir.getPath();
		createNotification(intent, this, title, text);
	}


	synchronized private void importData(Intent intent) {

		// Gets backup folder
		String backupName = intent.getStringExtra(INTENT_BACKUP_NAME);
		File backupDir = StorageManager.getBackupDir(backupName);

		// Database backup
		importDB(backupDir);

		// Attachments backup
		importAttachments(backupDir);

		// Settings restore
		importSettings(backupDir);

		String title = getString(R.string.data_import_completed);
		String text = getString(R.string.click_to_refresh_application);
		createNotification(intent, this, title, text);
	}


	/**
	 * Imports notes and notebooks from Springpad exported archive
	 * 
	 * @param intent
	 */
	synchronized private void importDataFromSpringpad(Intent intent) {

		// Backupped notes retrieval
		String backupPath = intent.getStringExtra(EXTRA_SPRINGPAD_BACKUP);
		Importer importer = new Importer();
		try {
			importer.setZipProgressesListener(new ZipProgressesListener() {
				@Override
				public void onZipProgress(int percentage) {
					mNotificationsHelper.setMessage(getString(R.string.extracted) + " " + percentage + "%").show();
				}
			});
			importer.doImport(backupPath);
			// Updating notification
			updateImportNotification(importer);
		} catch (ImportException e) {
			new NotificationsHelper(this)
					.createNotification(R.drawable.ic_stat_notification_icon,
							getString(R.string.import_fail) + ": " + e.getMessage(), null).show();
			return;
		}
		List<SpringpadElement> elements = importer.getSpringpadNotes();

		// If nothing is retrieved it will exit
		if (elements == null || elements.size() == 0) { return; }

		// These maps are used to associate with post processing notes to categories (notebooks)
		HashMap<String, Category> categoriesWithUuid = new HashMap<String, Category>();
		
		// Adds all the notebooks (categories)
		for (SpringpadElement springpadElement : importer.getNotebooks()) {
			Category cat = new Category();
			cat.setName(springpadElement.getName());
			cat.setColor(String.valueOf(Color.parseColor("#F9EA1B")));
			DbHelper.getInstance(this).updateCategory(cat);
			categoriesWithUuid.put(springpadElement.getUuid(), cat);
			
			// Updating notification
			importedSpringpadNotebooks++;
			updateImportNotification(importer);
		}
		// And creates a default one for notes without notebook 
		Category defaulCategory = new Category();
		defaulCategory.setName("Springpad");
		defaulCategory.setColor(String.valueOf(Color.parseColor("#F9EA1B")));
		DbHelper.getInstance(this).updateCategory(defaulCategory);
		
		// And then notes are created
		Note note;
		Attachment mAttachment = null;
		Uri uri = null;
		for (SpringpadElement springpadElement : importer.getNotes()) {
			note = new Note();

			// Title
			note.setTitle(springpadElement.getName());

			// Content dependent from type of Springpad note
			StringBuilder content = new StringBuilder();
			content.append(TextUtils.isEmpty(springpadElement.getText()) ? "" : Html.fromHtml(springpadElement
					.getText()));
			content.append(TextUtils.isEmpty(springpadElement.getDescription()) ? "" : springpadElement
					.getDescription());

			// Some notes could have been exported wrongly
			if (springpadElement.getType() == null) {
				Toast.makeText(this, getString(R.string.error), Toast.LENGTH_SHORT).show();
				continue;
			}
			
			if (springpadElement.getType().equals(SpringpadElement.TYPE_VIDEO)) {
				try {
					content.append(System.getProperty("line.separator")).append(springpadElement.getVideos().get(0));
				} catch (IndexOutOfBoundsException e) {
					content.append(System.getProperty("line.separator")).append(springpadElement.getUrl());
				}
			}
			if (springpadElement.getType().equals(SpringpadElement.TYPE_TVSHOW)) {
				content.append(System.getProperty("line.separator")).append(
						TextUtils.join(", ", springpadElement.getCast()));
			}
			if (springpadElement.getType().equals(SpringpadElement.TYPE_BOOK)) {
				content.append(System.getProperty("line.separator")).append("Author: ")
						.append(springpadElement.getAuthor()).append(System.getProperty("line.separator"))
						.append("Publication date: ").append(springpadElement.getPublicationDate());
			}
			if (springpadElement.getType().equals(SpringpadElement.TYPE_RECIPE)) {
				content.append(System.getProperty("line.separator")).append("Ingredients: ")
						.append(springpadElement.getIngredients()).append(System.getProperty("line.separator"))
						.append("Directions: ").append(springpadElement.getDirections());
			}
			if (springpadElement.getType().equals(SpringpadElement.TYPE_BOOKMARK)) {
				content.append(System.getProperty("line.separator")).append(springpadElement.getUrl());
			}
			if (springpadElement.getType().equals(SpringpadElement.TYPE_BUSINESS)
					&& springpadElement.getPhoneNumbers() != null) {
				content.append(System.getProperty("line.separator")).append("Phone number: ")
						.append(springpadElement.getPhoneNumbers().getPhone());
			}
			if (springpadElement.getType().equals(SpringpadElement.TYPE_PRODUCT)) {
				content.append(System.getProperty("line.separator")).append("Category: ")
						.append(springpadElement.getCategory()).append(System.getProperty("line.separator"))
						.append("Manufacturer: ").append(springpadElement.getManufacturer())
						.append(System.getProperty("line.separator")).append("Price: ")
						.append(springpadElement.getPrice());
			}
			if (springpadElement.getType().equals(SpringpadElement.TYPE_WINE)) {
				content.append(System.getProperty("line.separator")).append("Wine type: ")
						.append(springpadElement.getWine_type()).append(System.getProperty("line.separator"))
						.append("Varietal: ").append(springpadElement.getVarietal())
						.append(System.getProperty("line.separator")).append("Price: ")
						.append(springpadElement.getPrice());
			}
			if (springpadElement.getType().equals(SpringpadElement.TYPE_ALBUM)) {
				content.append(System.getProperty("line.separator")).append("Artist: ")
						.append(springpadElement.getArtist());
			}
			for (SpringpadComment springpadComment : springpadElement.getComments()) {
				content.append(System.getProperty("line.separator")).append(springpadComment.getCommenter())
						.append(" commented at 0").append(springpadComment.getDate()).append(": ")
						.append(springpadElement.getArtist());
			}

			note.setContent(content.toString());

			// Checklists
			if (springpadElement.getType().equals(SpringpadElement.TYPE_CHECKLIST)) {
				StringBuilder sb = new StringBuilder();
				String checkmark;
				for (SpringpadItem mSpringpadItem : springpadElement.getItems()) {
					checkmark = mSpringpadItem.getComplete() ? it.feio.android.checklistview.interfaces.Constants.CHECKED_SYM
							: it.feio.android.checklistview.interfaces.Constants.UNCHECKED_SYM;
					sb.append(checkmark).append(mSpringpadItem.getName()).append(System.getProperty("line.separator"));
				}
				note.setContent(sb.toString());
				note.setChecklist(true);
			}

			// Tags
			String tags = springpadElement.getTags().size() > 0 ? "#"
					+ TextUtils.join(" #", springpadElement.getTags()) : "";
			if (note.isChecklist()) {
				note.setTitle(note.getTitle() + tags);
			} else {
				note.setContent(note.getContent() + System.getProperty("line.separator") + tags);
			}

			// Address
			String address = springpadElement.getAddresses() != null ? springpadElement.getAddresses().getAddress()
					: "";
			if (!TextUtils.isEmpty(address)) {
				try {
					double[] coords = GeocodeHelper.getCoordinatesFromAddress(this, address);
					note.setLatitude(coords[0]);
					note.setLongitude(coords[1]);
				} catch (IOException e) {
					Log.e(Constants.TAG,
							"An error occurred trying to resolve address to coords during Springpad import");
				}
				note.setAddress(address);
			}

			// Reminder
			if (springpadElement.getDate() != null) {
				note.setAlarm(springpadElement.getDate().getTime());
			}

			// Creation, modification, category
			note.setCreation(springpadElement.getCreated().getTime());
			note.setLastModification(springpadElement.getModified().getTime());

			// Image
			String image = springpadElement.getImage();			
			if (!TextUtils.isEmpty(image)) {
				try {
					File file = StorageManager.createNewAttachmentFileFromHttp(this, image);
					uri = Uri.fromFile(file);
					String mimeType = StorageManager.getMimeType(uri.getPath());
					mAttachment = new Attachment(uri, mimeType);
				} catch (MalformedURLException e) {
					uri = Uri.parse(importer.getWorkingPath() + image);
					mAttachment = StorageManager.createAttachmentFromUri(this, uri, true);
				} catch (IOException e) {
					Log.e(Constants.TAG, "Error retrieving Springpad online image");
				}
				if (mAttachment != null) {
					note.addAttachment(mAttachment);
				}
				mAttachment = null;
			}
			
			// Other attachments
			for (SpringpadAttachment springpadAttachment : springpadElement.getAttachments()) {
				// The attachment could be the image itself so it's jumped
				if (image != null && image.equals(springpadAttachment.getUrl())) continue;
				
				if (TextUtils.isEmpty(springpadAttachment.getUrl())) {
					continue;
				};
				
				// Tries first with online images
				try {
					File file = StorageManager.createNewAttachmentFileFromHttp(this, springpadAttachment.getUrl());
					uri = Uri.fromFile(file);
					String mimeType = StorageManager.getMimeType(uri.getPath());
					mAttachment = new Attachment(uri, mimeType);
				} catch (MalformedURLException e) {
					uri = Uri.parse(importer.getWorkingPath() + springpadAttachment.getUrl());
					mAttachment = StorageManager.createAttachmentFromUri(this, uri, true);
				} catch (IOException e) {
					Log.e(Constants.TAG, "Error retrieving Springpad online image");
				}
				if (mAttachment != null) {
					note.addAttachment(mAttachment);
				}
				uri = null;
				mAttachment = null;
			}

			// If the note has a category is added to the map to be post-processed
			if (springpadElement.getNotebooks().size() > 0) {
				note.setCategory(categoriesWithUuid.get(springpadElement.getNotebooks().get(0)));
			} else {
				note.setCategory(defaulCategory);				
			}

			// The note is saved
			DbHelper.getInstance(this).updateNote(note, false);
			
			// Updating notification
			importedSpringpadNotes++;
			updateImportNotification(importer);
		};
		
		// Delete temp data
		try {
			importer.clean();
		} catch (IOException e) {
			Log.w(Constants.TAG, "Springpad import temp files not deleted");
		}

		String title = getString(R.string.data_import_completed);
		String text = getString(R.string.click_to_refresh_application);
		createNotification(intent, this, title, text);
	}


	private void updateImportNotification(Importer importer) {
		mNotificationsHelper.setMessage(
				importer.getNotebooksCount() + " " + getString(R.string.categories) + " ("
						+ importedSpringpadNotebooks + " " + getString(R.string.imported) + "), "
						+ +importer.getNotesCount() + " " + getString(R.string.notes) + " ("
						+ importedSpringpadNotes + " " + getString(R.string.imported) + ")").show();
	}


	synchronized private void deleteData(Intent intent) {

		// Gets backup folder
		String backupName = intent.getStringExtra(INTENT_BACKUP_NAME);
		File backupDir = StorageManager.getBackupDir(backupName);

		// Backup directory removal
		StorageManager.delete(this, backupDir.getAbsolutePath());

		String title = getString(R.string.data_deletion_completed);
		String text = backupName + " " + getString(R.string.deleted);
		createNotification(intent, this, title, text);
	}


	/**
	 * Creation of notification on operations completed
	 * 
	 * @param intent2
	 * @param ctx
	 * @param message
	 */
	private void createNotification(Intent intent, Context mContext, String title, String message) {

		// The behavior differs depending on intent action
		Intent intentLaunch;
		if (DataBackupIntentService.ACTION_DATA_IMPORT.equals(intent.getAction())
				|| DataBackupIntentService.ACTION_DATA_IMPORT_SPRINGPAD.equals(intent.getAction())) {
			intentLaunch = new Intent(mContext, MainActivity.class);
			intentLaunch.setAction(Constants.ACTION_RESTART_APP);
		} else {
			intentLaunch = new Intent();
		}
		// Add this bundle to the intent
		intentLaunch.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
		intentLaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		// Creates the PendingIntent
		PendingIntent notifyIntent = PendingIntent.getActivity(mContext, 0, intentLaunch,
				PendingIntent.FLAG_UPDATE_CURRENT);

		NotificationsHelper mNotificationsHelper = new NotificationsHelper(mContext);
		mNotificationsHelper.createNotification(R.drawable.ic_stat_notification_icon, title, notifyIntent)
				.setMessage(message).setRingtone(prefs.getString("settings_notification_ringtone", null));
		if (prefs.getBoolean("settings_notification_vibration", true)) mNotificationsHelper.setVibration();
		mNotificationsHelper.show();
	}


	/**
	 * Export database to backup folder
	 * 
	 * @param backupDir
	 * @return True if success, false otherwise
	 */
	private boolean exportDB(File backupDir) {
		File database = getDatabasePath(Constants.DATABASE_NAME);
		return (StorageManager.copyFile(database, new File(backupDir, Constants.DATABASE_NAME)));
	}


	/**
	 * Export attachments to backup folder
	 * 
	 * @param backupDir
	 * @return True if success, false otherwise
	 */
	private boolean exportAttachments(File backupDir) {
		File attachmentsDir = StorageManager.getAttachmentDir(this);
		File destinationattachmentsDir = new File(backupDir, attachmentsDir.getName());

		DbHelper db = DbHelper.getInstance(this);
		ArrayList<Attachment> list = db.getAllAttachments();

		int exported = 0;
		for (Attachment attachment : list) {
			StorageManager.copyToBackupDir(destinationattachmentsDir, new File(attachment.getUri().getPath()));
			mNotificationsHelper.setMessage(TextHelper.capitalize(getString(R.string.attachment)) + " " + exported++ + "/" + list.size())
					.show();
		}
		return true;
	}


	/**
	 * Exports settings if required
	 * 
	 * @param backupDir
	 * @return
	 */
	private boolean exportSettings(File backupDir) {
		File preferences = StorageManager.getSharedPreferencesFile(this);
		return (StorageManager.copyFile(preferences, new File(backupDir, preferences.getName())));
	}


	/**
	 * Imports settings
	 * 
	 * @param backupDir
	 * @return
	 */
	private boolean importSettings(File backupDir) {
		File preferences = StorageManager.getSharedPreferencesFile(this);
		File preferenceBackup = new File(backupDir, preferences.getName());
		return (StorageManager.copyFile(preferenceBackup, preferences));
	}


	/**
	 * Import database from backup folder
	 * 
	 * @param backupDir
	 * @return True if success, false otherwise
	 */
	private boolean importDB(File backupDir) {
		File database = getDatabasePath(Constants.DATABASE_NAME);
		if (database.exists()) {
			database.delete();
		}
		return (StorageManager.copyFile(new File(backupDir, Constants.DATABASE_NAME), database));
	}


	/**
	 * Import attachments from backup folder
	 * 
	 * @param backupDir
	 * @return True if success, false otherwise
	 */
	private boolean importAttachments(File backupDir) {
		File attachmentsDir = StorageManager.getAttachmentDir(this);
		// Clearing
		StorageManager.delete(this, attachmentsDir.getAbsolutePath());
		// Moving back
		File backupAttachmentsDir = new File(backupDir, attachmentsDir.getName());
		
		boolean result = true;
		Collection list = FileUtils.listFiles(backupAttachmentsDir, FileFilterUtils.trueFileFilter(),
				TrueFileFilter.INSTANCE);
		Iterator<File> i = list.iterator();
		int imported = 0;
		File file = null;
		while (i.hasNext()) {
			try {
				file = i.next();
				FileUtils.copyFileToDirectory(file, attachmentsDir, true);
				mNotificationsHelper.setMessage(TextHelper.capitalize(getString(R.string.attachment)) + " " + imported++ + "/" + list.size())
						.show();
			} catch (IOException e) {
				result = false;
				Log.e(Constants.TAG, "Error importing the attachment " + file.getName());
			}
		}
		return result;
	}


	@Override
	public void onAttachingFileErrorOccurred(Attachment mAttachment) {
		// TODO Auto-generated method stub

	}


	@Override
	public void onAttachingFileFinished(Attachment mAttachment) {
		// TODO Auto-generated method stub

	}

}
