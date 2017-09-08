package com.nilhcem.fakesmtp.server;

import com.nilhcem.fakesmtp.core.ArgsHandler;
import com.nilhcem.fakesmtp.core.Configuration;
import com.nilhcem.fakesmtp.core.I18n;
import com.nilhcem.fakesmtp.model.EmailModel;
import com.nilhcem.fakesmtp.model.FilesModel;
import com.nilhcem.fakesmtp.model.UIModel;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Observable;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.activation.DataHandler;
import javax.mail.BodyPart;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;

/**
 * Saves emails and notifies components so they can refresh their views with new data.
 *
 * @author Nilhcem
 * @since 1.0
 */
public final class MailSaver extends Observable {

	private static final Logger LOGGER = LoggerFactory.getLogger(MailSaver.class);
	private static final String LINE_SEPARATOR = System.getProperty("line.separator");
	// This can be a static variable since it is Thread Safe
	private static final Pattern SUBJECT_PATTERN = Pattern.compile("^Subject: (.*)$");

	private final SimpleDateFormat dateFormat = new SimpleDateFormat("ddMMyyhhmmssSSS");

	/**
	 * Saves incoming email in file system and notifies observers.
	 *
	 * @param from the user who send the email.
	 * @param to the recipient of the email.
	 * @param data an InputStream object containing the email.
	 * @see com.nilhcem.fakesmtp.gui.MainPanel#addObservers to see which observers will be notified
	 */
	public void saveEmailAndNotify(String from, String to, InputStream data) {
		List<String> relayDomains = UIModel.INSTANCE.getRelayDomains();

		if (relayDomains != null) {
			boolean matches = false;
			for (String domain : relayDomains) {
				if (to.endsWith(domain)) {
					matches = true;
					break;
				}
			}

			if (!matches) {
				LOGGER.debug("Destination {} doesn't match relay domains", to);
				return;
			}
		}

		// We move everything that we can move outside the synchronized block to limit the impact
		EmailModel model = new EmailModel();
		model.setFrom(from);
		model.setTo(to);
		String mailContent = convertStreamToString(data);
		model.setSubject(getSubjectFromStr(mailContent));
		model.setEmailStr(mailContent);

		synchronized (getLock()) {
			String filePath = saveEmailToFile(mailContent);

			if(UIModel.INSTANCE.isSaveHtml()) {
				try {
					String htmlFilePath = saveHtmlToFile(new File(filePath));
					model.setHtmlFilePath(htmlFilePath);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}

			model.setReceivedDate(new Date());
			model.setFilePath(filePath);

			setChanged();
			notifyObservers(model);
		}
	}

	/**
	 * Deletes all received emails from file system.
	 */
	public void deleteEmails() {
		Map<Integer, FilesModel> mails = UIModel.INSTANCE.getListMailsMap();
		if (ArgsHandler.INSTANCE.memoryModeEnabled()) {
			return;
		}
		for (FilesModel value : mails.values()) {
			deleteFile(value.getEmlFilePath());
			deleteFile(value.getHtmlFilePath());
		}
	}

	private void deleteFile(String filename){
		File file = new File(filename);
		if (file.exists()) {
			try {
				if (!file.delete()) {
					LOGGER.error("Impossible to delete file {}", filename);
				}
			} catch (SecurityException e) {
				LOGGER.error("", e);
			}
		}
	}

	/**
	 * Returns a lock object.
	 * <p>
	 * This lock will be used to make the application thread-safe, and
	 * avoid receiving and deleting emails in the same time.
	 * </p>
	 *
	 * @return a lock object <i>(which is actually the current instance of the {@code MailSaver} object)</i>.
	 */
	public Object getLock() {
		return this;
	}

	/**
	 * Converts an {@code InputStream} into a {@code String} object.
	 * <p>
	 * The method will not copy the first 4 lines of the input stream.<br>
	 * These 4 lines are SubEtha SMTP additional information.
	 * </p>
	 *
	 * @param is the InputStream to be converted.
	 * @return the converted string object, containing data from the InputStream passed in parameters.
	 */
	private String convertStreamToString(InputStream is) {
		final long lineNbToStartCopy = 4; // Do not copy the first 4 lines (received part)
		BufferedReader reader = new BufferedReader(new InputStreamReader(is, Charset.forName(I18n.UTF8)));
		StringBuilder sb = new StringBuilder();

		String line;
		long lineNb = 0;
		try {
			while ((line = reader.readLine()) != null) {
				if (++lineNb > lineNbToStartCopy) {
					sb.append(line).append(LINE_SEPARATOR);
				}
			}
		} catch (IOException e) {
			LOGGER.error("", e);
		}
		return sb.toString();
	}

	/**
	 * Saves the content of the email passed in parameters in a file.
	 *
	 * @param mailContent the content of the email to be saved.
	 * @return the path of the created file.
	 */
	private String saveEmailToFile(String mailContent) {
		if (ArgsHandler.INSTANCE.memoryModeEnabled()) {
			return null;
		}
		String filePath = String.format("%s%s%s", UIModel.INSTANCE.getSavePath(), File.separator,
				dateFormat.format(new Date()));

		// Create file
		int i = 0;
		File file = null;
		while (file == null || file.exists()) {
			String iStr;
			if (i++ > 0) {
				iStr = Integer.toString(i);
			} else {
				iStr = "";
			}
			file = new File(filePath + iStr + Configuration.INSTANCE.get("emails.suffix"));
		}

		// Copy String to file
		try {
			FileUtils.writeStringToFile(file, mailContent);
		} catch (IOException e) {
			// If we can't save file, we display the error in the SMTP logs
			Logger smtpLogger = LoggerFactory.getLogger(org.subethamail.smtp.server.Session.class);
			smtpLogger.error("Error: Can't save email: {}", e.getMessage());
		}
		return file.getAbsolutePath();
	}

	/**
	 * Gets the subject from the email data passed in parameters.
	 *
	 * @param data a string representing the email content.
	 * @return the subject of the email, or an empty subject if not found.
	 */
	private String getSubjectFromStr(String data) {
		try {
			BufferedReader reader = new BufferedReader(new StringReader(data));

			String line;
			while ((line = reader.readLine()) != null) {
				 Matcher matcher = SUBJECT_PATTERN.matcher(line);
				 if (matcher.matches()) {
					 return matcher.group(1);
				 }
			}
		} catch (IOException e) {
			LOGGER.error("", e);
		}
		return "";
	}


	private String saveHtmlToFile(File emlFile) throws Exception{
		Properties props = System.getProperties();
		props.put("mail.host", "smtp.dummydomain.com");
		props.put("mail.transport.protocol", "smtp");

		Session mailSession = Session.getDefaultInstance(props, null);
		InputStream source = new FileInputStream(emlFile);
		MimeMessage message = new MimeMessage(mailSession, source);


		System.out.println("Subject : " + message.getSubject());
		System.out.println("From : " + message.getFrom()[0]);
		System.out.println("--------------");
		//System.out.println("Body : " +  message.getContent());
		String msgContent = getMessageContent(message.getContent());
		System.out.println("Body : \n" +  msgContent);


		String htmlFilePath = String.format("%s%s%s.html", UIModel.INSTANCE.getSavePath(), File.separator, FilenameUtils.getBaseName(emlFile.getAbsolutePath()));
		try {
			FileUtils.writeStringToFile(new File(htmlFilePath), msgContent);
		} catch (IOException e) {
			// If we can't save file, we display the error in the SMTP logs
			Logger smtpLogger = LoggerFactory.getLogger(org.subethamail.smtp.server.Session.class);
			smtpLogger.error("Error: Can't save email: {}", e.getMessage());
		}
		return htmlFilePath;
	}

	private String getMessageContent(Object msgContent) throws MessagingException, IOException {
		String content = "";

     /* Check if content is pure text/html or in parts */
		if (msgContent instanceof Multipart) {

			Multipart multipart = (Multipart) msgContent;

			System.out.println("BodyPart MultiPartCount: "+multipart.getCount());

			for (int j = 0; j < multipart.getCount(); j++) {

				BodyPart bodyPart = multipart.getBodyPart(j);

				String disposition = bodyPart.getDisposition();

				if (disposition != null && (disposition.equalsIgnoreCase("ATTACHMENT"))) {
					System.out.println("Mail have some attachment");

					DataHandler handler = bodyPart.getDataHandler();
					System.out.println("file name : " + handler.getName());
				}
				else {
					//content = getText(bodyPart);  // the changed code
					content = bodyPart.getContent().toString();
				}
			}
		}
		else {
			content = msgContent.toString();
		}
		return content;
	}
}
