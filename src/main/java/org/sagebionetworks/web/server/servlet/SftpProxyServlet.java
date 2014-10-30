package org.sagebionetworks.web.server.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.fileupload.FileItemIterator;
import org.apache.commons.fileupload.FileItemStream;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.sagebionetworks.repo.model.attachment.UploadResult;
import org.sagebionetworks.repo.model.attachment.UploadStatus;
import org.sagebionetworks.schema.adapter.JSONObjectAdapterException;
import org.sagebionetworks.schema.adapter.org.json.EntityFactory;
import org.sagebionetworks.web.server.servlet.filter.SFTPFileMetadata;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;

public class SftpProxyServlet extends HttpServlet {
	public static final String SFTP_CHANNEL_TYPE = "sftp";
	public static final String SFTP_URL_PARAM = "url";
	private static final long serialVersionUID = 1L;
	
	private JSch jsch = new JSch();
	protected static final ThreadLocal<HttpServletRequest> perThreadRequest = new ThreadLocal<HttpServletRequest>();

	@Override
	protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		SftpProxyServlet.perThreadRequest.set(request);
		super.service(request, response);
	}
	
	@Override
	public void service(ServletRequest request, ServletResponse response) throws ServletException, IOException {
		super.service(request, response);
	}
	
	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse response) throws ServletException, IOException {
		respondWithHtml(response, GET_RESPONSE, HttpServletResponse.SC_OK);
	}
	
	public void respondWithHtml(HttpServletResponse response, String message, int status) throws IOException {
		response.setStatus(HttpServletResponse.SC_OK);
		response.setContentType("text/html");
		response.setCharacterEncoding("UTF-8");
		String html = String.format(HTML_RESPONSE, message);
		byte[] outBytes = html.getBytes("UTF-8");
		response.getOutputStream().write(outBytes);
	}
	
	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		SFTPFileMetadata metadata = SFTPFileMetadata.parseUrl(request.getParameter(SFTP_URL_PARAM));
		try {
			//gather input values from the request
			ServletFileUpload upload = new ServletFileUpload();
			FileItemIterator iter = upload.getItemIterator(request);
			
			String username = null;
			String password = null;
			boolean uploading = false;
			while (iter.hasNext()) {
				FileItemStream item = iter.next();
				if (item.isFormField()) {
					String fieldValue = getStringItem(item);
					if (item.getFieldName().equals("username")) {
						username = fieldValue;
					} else if (item.getFieldName().equals("password")) {
						password = fieldValue;
					}
				} else {
					uploading = true;
					//the file
					Session session = getSession(username, password, metadata);
					sftpUploadFile(session, metadata, response, item);
				}
			}
			if (!uploading) {
				try {
					//download!
					Session session = getSession(username, password, metadata);
					sftpDownloadFile(session, metadata, response);
				} catch (Exception e) {
					respondWithHtml(response,e.getMessage(), HttpServletResponse.SC_BAD_REQUEST);
				}
			}
		} catch (Exception e) {
			fillResponseWithFailure(response, e);
		}
	}
	
	public String getStringItem(FileItemStream item) throws IOException {
		InputStream stream = null;
		try {
			stream = item.openStream();
			byte[] str = new byte[stream.available()];
			stream.read(str);
			return new String(str, "UTF8");
		} finally {
			if (stream != null)
				stream.close();
		}
	}
	
	public void sftpDownloadFile(Session session, SFTPFileMetadata metadata, HttpServletResponse response) throws IOException, JSchException, SftpException {
		ServletOutputStream stream = response.getOutputStream();
		try {
			Channel channel = session.openChannel(SFTP_CHANNEL_TYPE);
			channel.connect();
			ChannelSftp sftpChannel = (ChannelSftp) channel;
			sftpChannel.get(metadata.getDecodedSourcePath(), stream);
			sftpChannel.exit();
		} finally {
			if (session != null)
				session.disconnect();
		}
	}
	
	public void sftpUploadFile(Session session, SFTPFileMetadata metadata, HttpServletResponse response, FileItemStream item) throws FileUploadException, IOException, ServletException {
		String name = item.getFieldName();
		InputStream stream = item.openStream();
		
		String fileName = item.getName();
		if (fileName.contains("\\")) {
			fileName = fileName.substring(fileName.lastIndexOf("\\") + 1);
		}
		
		try {
			Channel channel = session.openChannel(SFTP_CHANNEL_TYPE);
			channel.connect();
			ChannelSftp sftpChannel = (ChannelSftp) channel;
			changeToRemoteUploadDirectory(metadata, sftpChannel);
			sftpChannel.put(stream, fileName);
			sftpChannel.exit();
			
			fillResponseWithSuccess(response, metadata.getFullEncodedUrl() + "/" + URLEncoder.encode(fileName, "UTF-8"));
		} catch (SecurityException e) {
			throw e;
		} catch (Exception e) {
			fillResponseWithFailure(response, e);
			return;
		} finally {
			if (session != null)
				session.disconnect();
		}
	}
	
	public void changeToRemoteUploadDirectory(SFTPFileMetadata metadata, ChannelSftp sftpChannel) throws SftpException {
		//change directory (and make directory if not exist)
		for (String directory : metadata.getPath()) {
			try{
				sftpChannel.cd(directory);
			} catch (SftpException e) {
				//cannot access, try to create and go there
				sftpChannel.mkdir(directory);
				sftpChannel.cd(directory);
			}
		}
	}
	
	public Session getSession(String username, String password, SFTPFileMetadata metadata) throws SecurityException {
		if (username == null || password == null) {
			throw new IllegalArgumentException("Authorization is required to establish the SFTP connection.");
		}

		Session session;
		try {
			session = jsch.getSession(username, metadata.getHost(), metadata.getPort());
			session.setPassword(password);
			session.setConfig("StrictHostKeyChecking", "no");
			session.connect();
		} catch (Throwable e) {
			throw new SecurityException(e);
		}
		return session;
	}
	
	/**
	 * For testing purposes
	 * @param jsch
	 */
	public void setJsch(JSch jsch) {
		this.jsch = jsch;
	}

	public static void fillResponseWithSuccess(HttpServletResponse response, String url) throws JSONObjectAdapterException, UnsupportedEncodingException, IOException {
		UploadResult result = new UploadResult();
		result.setMessage(url);
		
		result.setUploadStatus(UploadStatus.SUCCESS);
		String uploadResultJson = EntityFactory.createJSONStringForEntity(result);
		response.setStatus(HttpServletResponse.SC_CREATED);
		response.setContentType("text/html");
		response.setCharacterEncoding("UTF-8");
		String out = getPostMessageResponsePage(uploadResultJson);
		byte[] outBytes = out.getBytes("UTF-8");
		response.getOutputStream().write(outBytes);
	}
	
	public static void fillResponseWithFailure(HttpServletResponse response, Exception e) throws UnsupportedEncodingException, IOException {
		UploadResult result = new UploadResult();
		result.setMessage(e.getMessage());
		result.setUploadStatus(UploadStatus.FAILED);
		try {
			String uploadResultJson = EntityFactory.createJSONStringForEntity(result);
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			response.setContentType("text/html");
			response.setCharacterEncoding("UTF-8");
			String out = getPostMessageResponsePage(uploadResultJson);
			byte[] outBytes = out.getBytes("UTF-8");
			response.getOutputStream().write(outBytes);
		} catch (JSONObjectAdapterException e1) {
			throw new RuntimeException(e1);
		}
	}
	
	public static String getPostMessageResponsePage(String uploadResultJson) {
		return String.format(RESPONSE_HTML, uploadResultJson);
	}
	
	public static final String RESPONSE_HTML = "<html>\n" + 
			"  <body onload=\"javascript:sendMessage()\">\n" + 
			"	<script>\n" + 
			"		function sendMessage() {\n" + 
			"			window.parent.postMessage('%s', '*');\n" + 
			"		}\n" + 
			"	</script>\n" + 
			"  </body>\n" + 
			"</html>";
	
	public static final String GET_RESPONSE = "<h1>The Synapse SFTP proxy is running</h1>";
	public static final String HTML_RESPONSE =
			"<html>\n" + 
			"  <body>\n" +
			"	%s\n"+
			"  </body>\n" + 
			"</html>";
}
