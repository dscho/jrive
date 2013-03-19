import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.java6.auth.oauth2.FileCredentialStore;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.media.MediaHttpDownloader;
import com.google.api.client.googleapis.media.MediaHttpDownloaderProgressListener;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.Drive.Changes;
import com.google.api.services.drive.Drive.Files;
import com.google.api.services.drive.Drive.Files.Get;
import com.google.api.services.drive.Drive.Revisions;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.Change;
import com.google.api.services.drive.model.ChangeList;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.Revision;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.scijava.util.ProcessUtils;

public class CommandLine {

	private static String CLIENT_ID = "974272400485.apps.googleusercontent.com";
	private static String CLIENT_SECRET = "HUZk4uFBqoDGr9V11No2ce9K";

	/**
	 * Be sure to specify the name of your application. If the application name
	 * is {@code null} or blank, the application will log a warning. Suggested
	 * format is "MyCompany-ProductName/1.0".
	 */
	private static final String APPLICATION_NAME = "DschosDriveClient/1.0.0";

	/** Authorizes the installed application to access user's protected data. */
	private static Credential authorize(final JsonFactory jsonFactory,
			final HttpTransport httpTransport) throws Exception {
		final FileCredentialStore credentialStore = new FileCredentialStore(
				new java.io.File(System.getProperty("user.home"),
						".credentials/drive.json"), jsonFactory);
		final GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
				httpTransport, jsonFactory, CLIENT_ID, CLIENT_SECRET,
				Collections.singleton(DriveScopes.DRIVE)).setCredentialStore(
				credentialStore).build();
		return new AuthorizationCodeInstalledApp(flow,
				new LocalServerReceiver()).authorize("user");
	}

	public static void main(final String[] args) {
		boolean force = false;
		boolean showRevisions = false;
		boolean fastExport = false;
		String fastExportFrom = null;
		int opt = 1;

		int i;
		for (i = 0; i < args.length && args[i].startsWith("-"); i++) {
			final String arg = args[i];
			if (arg.equals("-f") || arg.equals("--force")) {
				force = true;
			} else if (arg.equals("--revisions")) {
				showRevisions = true;
			} else if (arg.equals("--fast-export")) {
				fastExport = true;
				if (args.length == i + 3) {
					fastExportFrom = args[i + 2];
					opt++;
				}
			} else {
				System.err.println("Unknown flag: " + arg);
				System.exit(1);
			}
		}

		if (args.length != i + opt) {
			System.err.println("Usage: " + CommandLine.class + " [-f] <id>");
			System.exit(1);
		}
		final String gdocId = args[i];

		final HttpTransport httpTransport = new NetHttpTransport();

		final JsonFactory jsonFactory = new JacksonFactory();
		try {
			final Credential credential = authorize(jsonFactory, httpTransport);
			final Drive drive = new Drive.Builder(httpTransport, jsonFactory,
					credential).setApplicationName(APPLICATION_NAME).build();

			if (showRevisions) {
				showRevisions(drive, gdocId);
			} else if (fastExport) {
				final String outFileName = gdocId + ".export";
				final OutputStream out = new FileOutputStream(outFileName);
				gitFastExport(drive, gdocId, fastExportFrom, out);
				out.close();
				System.err.println("Exported to " + outFileName);
			} else {
				download(drive, gdocId, force);
			}
		} catch (final Exception e) {
			e.printStackTrace();
		}
	}

	protected static void download(final Drive drive, final String gdocId,
			final boolean forceOverwrite) throws Exception {
		final Files files = drive.files();
		final Get get = files.get(gdocId);
		final File file2 = get.execute();
		final String url = file2.getExportLinks().get(
				"application/vnd.oasis.opendocument.text");

		final String fileName = getFileName(file2.getTitle());
		final java.io.File file = new java.io.File(fileName);
		if (!forceOverwrite && file.exists()) {
			System.err.println("File " + file + " already exists; skipping");
			System.exit(1);
		}
		final OutputStream out = new FileOutputStream(file);
		final MediaHttpDownloader downloader = get.getMediaHttpDownloader();
		download(downloader, url, out);
		System.err.println("\rDownloaded " + file.getAbsolutePath());
	}

	protected static void download(final MediaHttpDownloader downloader, final String url, final OutputStream out) throws Exception {
		downloader.setProgressListener(getProgressListener());
		downloader.download(new GenericUrl(url), out);
		out.close();
	}

	protected static String getFileName(final String originalFileName) {
		return originalFileName.replace(" ", "") + ".odt";
	}

	protected static MediaHttpDownloaderProgressListener getProgressListener() {
		return new MediaHttpDownloaderProgressListener() {

			public void progressChanged(final MediaHttpDownloader downloader2)
					throws IOException {
				System.err.print("\r" + downloader2.getProgress()
						+ "...           ");
			}

		};
	}

	protected static void showRevisions(final Drive drive, final String gdocId)
			throws Exception {
		final Revisions revisions = drive.revisions();
		final Revision revision1 = revisions.get(gdocId, "head").execute();
		System.err.println("revision: " + revision1.getId() + "; " + revision1.getLastModifyingUserName() + " " + revision1.getModifiedDate());
		for (final Revision revision : revisions.list(gdocId).execute()
				.getItems()) {
			System.err.println("revision: " + revision.getId() + "; " + revision.getLastModifyingUserName() + " " + revision.getModifiedDate());
		}
	}

	protected static void gitFastExport(final Drive drive, final String gdocId, String fromRevisionId, final OutputStream out) throws Exception {
		final Revisions revisions = drive.revisions();
		System.err.println("Getting head revision of " + gdocId);
		final Revision head = revisions.get(gdocId, "head").execute();

		Get get = drive.files().get(gdocId);
		final String fileName = getFileName(get.execute().getTitle());

		if ("from-git".equals(fromRevisionId)) {
			final String commitSubject = git("log", "--format=%s", "-1", "--", fileName);
			if (commitSubject.startsWith("Revision ")) {
				fromRevisionId = commitSubject.substring(9);
			} else {
				fromRevisionId = null;
			}
		}

		if (head.getId().equals(fromRevisionId)) return;

		System.err.println("Getting revision list for " + fileName);
		final Comparator<Revision> comparator = new Comparator<Revision>() {
			//@Override
			public int compare(final Revision a, final Revision b) {
				long diff = a.getModifiedDate().getValue() - b.getModifiedDate().getValue();
				return diff < 0 ? -1 : (diff > 0 ? 1 : 0);
			}
		};
		final Set<Revision> toDownload = new TreeSet<Revision>(comparator);
		long fromMillis = -1;
		for (final Revision revision : revisions.list(gdocId).execute()
				.getItems()) {
			if (revision.getId().equals(fromRevisionId)) {
				fromMillis = revision.getModifiedDate().getValue();
			}
			toDownload.add(revision);
		}

		int mark = 1;
		for (final Revision revision : toDownload) {
			if (revision.getModifiedDate().getValue() <= fromMillis) continue;

			System.err.println("Downloading revision " + revision.getId() + " (" + mark + "/" + toDownload.size() + ")");
			get = drive.files().get(gdocId);
			final MediaHttpDownloader downloader = get.getMediaHttpDownloader();
			Long fileSize = revision.getFileSize();
			final ByteArrayOutputStream buffer = new ByteArrayOutputStream(fileSize == null ? 16384 : (int)fileSize.longValue());
			download(downloader, revision.getExportLinks().get(
				"application/vnd.oasis.opendocument.text"), buffer);
			byte[] contents = buffer.toByteArray();
			final String header = "blob\nmark :" + mark + "\ndata " + contents.length + "\n";
			out.write(header.getBytes("UTF-8"));
			out.write(contents);
			final String author = revision.getLastModifyingUserName();
			final DateTime date = revision.getModifiedDate();
			final String dateString = formatTime(date.getValue(), date.getTimeZoneShift());
			final String authorLine = "author " + (author == null ? "unknown <unknown" : author + " <" + author) + "@gmail.com> " + dateString;
			final String committerLine = "committer Jrive <jrive@dscho.org> " + dateString;
			final byte[] commitSubject = ("Revision " + revision.getId() + "\n").getBytes("UTF-8");
			final byte[] commit = ("\ncommit refs/heads/master\n"
					+ authorLine + "\n" + committerLine
					+ "\ndata " + commitSubject.length + "\n").getBytes("UTF-8");
			out.write(commit);
			out.write(commitSubject);
			final byte[] indexLine = ("M 100644 :" + mark++ + " " + fileName + "\n\n").getBytes("UTF-8");
			out.write(indexLine);
		}
		out.flush();
	}

	protected static String formatTime(long millis, int tz) {
		return String.format("%d %+05d", millis / 1000l, tz);
	}

	protected static void showChanges(final Drive drive) throws Exception {
		// run commands
		final List<Change> result = new ArrayList<Change>();
		final Changes.List request = drive.changes().list();
		request.setStartChangeId(1343l);

		do {
			try {
				final ChangeList changes = request.execute();

				result.addAll(changes.getItems());
				request.setPageToken(changes.getNextPageToken());
			} catch (final IOException e) {
				System.out.println("An error occurred: " + e);
				request.setPageToken(null);
			}
		} while (request.getPageToken() != null
				&& request.getPageToken().length() > 0);

		for (final Change change : result) {
			final File file = change.getFile();
			System.err.println("change: "
					+ change.get("createdDate")
					+ " "
					+ (file == null ? "(nobody)" : file
							.getLastModifyingUserName()) + " modified "
					+ (file == null ? "(null)" : file.getTitle()));
		}
	}

	protected static String git(final String... args) {
		final String[] commandLine = new String[args.length + 1];
		commandLine[0] = "git";
		System.arraycopy(args, 0, commandLine, 1, args.length);
		return ProcessUtils.exec(null, System.err, null, commandLine);
	}
}
