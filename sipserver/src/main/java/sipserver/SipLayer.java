package sipserver;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Properties;
import java.util.TooManyListenersException;

import javax.sip.ClientTransaction;
import javax.sip.Dialog;
import javax.sip.DialogTerminatedEvent;
import javax.sip.IOExceptionEvent;
import javax.sip.InvalidArgumentException;
import javax.sip.ListeningPoint;
import javax.sip.ObjectInUseException;
import javax.sip.PeerUnavailableException;
import javax.sip.RequestEvent;
import javax.sip.ResponseEvent;
import javax.sip.ServerTransaction;
import javax.sip.SipException;
import javax.sip.SipFactory;
import javax.sip.SipListener;
import javax.sip.SipProvider;
import javax.sip.SipStack;
import javax.sip.TimeoutEvent;
import javax.sip.TransactionState;
import javax.sip.TransactionTerminatedEvent;
import javax.sip.TransportNotSupportedException;
import javax.sip.address.Address;
import javax.sip.address.AddressFactory;
import javax.sip.address.SipURI;
import javax.sip.header.CSeqHeader;
import javax.sip.header.CallIdHeader;
import javax.sip.header.ContactHeader;
import javax.sip.header.ContentTypeHeader;
import javax.sip.header.FromHeader;
import javax.sip.header.HeaderFactory;
import javax.sip.header.MaxForwardsHeader;
import javax.sip.header.ToHeader;
import javax.sip.header.ViaHeader;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;

public class SipLayer implements SipListener {

	private MessageProcessor messageProcessor;

	private String username;

	private SipStack sipStack;

	private SipFactory sipFactory;

	private AddressFactory addressFactory;

	private HeaderFactory headerFactory;

	private MessageFactory messageFactory;

	private SipProvider sipProvider;

	private Dialog dialog;
	private Response okResponse;
	protected ServerTransaction inviteTid;
	private Request inviteRequest;

	public SipLayer(String username, String ip, int port) throws PeerUnavailableException,
			TransportNotSupportedException, InvalidArgumentException, ObjectInUseException, TooManyListenersException {
		setUsername(username);
		sipFactory = SipFactory.getInstance();
		sipFactory.setPathName("gov.nist");
		Properties properties = new Properties();
		properties.setProperty("javax.sip.STACK_NAME", "Igneel");
		properties.setProperty("javax.sip.IP_ADDRESS", ip);

		properties.setProperty("gov.nist.javax.sip.TRACE_LEVEL", "32");
		properties.setProperty("gov.nist.javax.sip.SERVER_LOG", "textclient.txt");
		properties.setProperty("gov.nist.javax.sip.DEBUG_LOG", "textclientdebug.log");

		sipStack = sipFactory.createSipStack(properties);
		headerFactory = sipFactory.createHeaderFactory();
		addressFactory = sipFactory.createAddressFactory();
		messageFactory = sipFactory.createMessageFactory();

		ListeningPoint tcp = sipStack.createListeningPoint(port, "tcp");
		ListeningPoint udp = sipStack.createListeningPoint(port, "udp");

		sipProvider = sipStack.createSipProvider(tcp);
		sipProvider.addSipListener(this);
		sipProvider = sipStack.createSipProvider(udp);
		sipProvider.addSipListener(this);
	}

	public void sendMessage(String to, String message) throws ParseException, InvalidArgumentException, SipException {

		SipURI from = addressFactory.createSipURI(getUsername(), getHost() + ":" + getPort());
		Address fromNameAddress = addressFactory.createAddress(from);
		fromNameAddress.setDisplayName(getUsername());
		FromHeader fromHeader = headerFactory.createFromHeader(fromNameAddress, "Igneel");

		String username = to.substring(to.indexOf(":") + 1, to.indexOf("@"));
		String address = to.substring(to.indexOf("@") + 1);

		SipURI toAddress = addressFactory.createSipURI(username, address);
		Address toNameAddress = addressFactory.createAddress(toAddress);
		toNameAddress.setDisplayName(username);
		ToHeader toHeader = headerFactory.createToHeader(toNameAddress, null);

		SipURI requestURI = addressFactory.createSipURI(username, address);
		requestURI.setTransportParam("udp");

		ArrayList viaHeaders = new ArrayList();
		ViaHeader viaHeader = headerFactory.createViaHeader(getHost(), getPort(), "udp", "branch1");
		viaHeaders.add(viaHeader);

		CallIdHeader callIdHeader = sipProvider.getNewCallId();

		CSeqHeader cSeqHeader = headerFactory.createCSeqHeader(1, Request.INVITE);

		MaxForwardsHeader maxForwards = headerFactory.createMaxForwardsHeader(70);

		Request inviteRequest = messageFactory.createRequest(requestURI, Request.INVITE, callIdHeader, cSeqHeader,
				fromHeader, toHeader, viaHeaders, maxForwards);

		SipURI contactURI = addressFactory.createSipURI(getUsername(), getHost());
		contactURI.setPort(getPort());
		Address contactAddress = addressFactory.createAddress(contactURI);
		contactAddress.setDisplayName(getUsername());
		ContactHeader contactHeader = headerFactory.createContactHeader(contactAddress);
		inviteRequest.addHeader(contactHeader);

		ContentTypeHeader contentTypeHeader = headerFactory.createContentTypeHeader("text", "plain");
		inviteRequest.setContent(message, contentTypeHeader);

		ClientTransaction trans = sipProvider.getNewClientTransaction(inviteRequest);
		dialog = trans.getDialog();
		trans.sendRequest();
	}

	public void processResponse(ResponseEvent evt) {
		Response response = evt.getResponse();
		int status = response.getStatusCode();
		System.out.println("Status: " + status);
		try {
			System.out.println("processResponse success");
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public void processRequest(RequestEvent requestEvent) {

		Request request = requestEvent.getRequest();
		ServerTransaction serverTransactionId = requestEvent.getServerTransaction();

		if (request.getMethod().equals(Request.INVITE)) {
			processInvite(requestEvent, serverTransactionId);
		} else if (request.getMethod().equals(Request.ACK)) {
			processBye();
//		} else if (request.getMethod().equals(Request.BYE)) {
//			processBye(requestEvent, serverTransactionId);
//		} else if (request.getMethod().equals(Request.CANCEL)) {
//			processCancel(requestEvent, serverTransactionId);
		}
	}

	public void processTimeout(TimeoutEvent evt) {
		messageProcessor.processError("Previous message not sent: " + "timeout");
	}

	public void processIOException(IOExceptionEvent evt) {
		messageProcessor.processError("Previous message not sent: " + "I/O Exception");
	}

	public void processDialogTerminated(DialogTerminatedEvent evt) {
		// use this method to destroy the objects
	}

	public void processTransactionTerminated(TransactionTerminatedEvent evt) {
		// use this method to destroy the objects
	}

	public String getHost() {
		int port = sipProvider.getListeningPoint().getPort();
		String host = sipStack.getIPAddress();
		return host;
	}

	public int getPort() {
		int port = sipProvider.getListeningPoint().getPort();
		return port;
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(String newUsername) {
		username = newUsername;
	}

	public MessageProcessor getMessageProcessor() {
		return messageProcessor;
	}

	public void setMessageProcessor(MessageProcessor newMessageProcessor) {
		messageProcessor = newMessageProcessor;
	}

	public void processInvite(RequestEvent requestEvent, ServerTransaction serverTransaction) {
		SipProvider sipProvider = (SipProvider) requestEvent.getSource();
		Request request = requestEvent.getRequest();
		try {
			System.out.println("processInvite method");
			Response response = messageFactory.createResponse(Response.RINGING, request);
			ServerTransaction st = requestEvent.getServerTransaction();
			if (st == null) {
				st = sipProvider.getNewServerTransaction(request);
			}
			dialog = st.getDialog();
			st.sendResponse(response);

			this.okResponse = messageFactory.createResponse(Response.OK, request);
			Address address = addressFactory.createAddress("From <sip:" + getHost() + ":" + getPort() + ">");
			ContactHeader contactHeader = headerFactory.createContactHeader(address);
			response.addHeader(contactHeader);
			ToHeader toHeader = (ToHeader) okResponse.getHeader(ToHeader.NAME);
			toHeader.setTag("4321"); // Application is supposed to set.
			okResponse.addHeader(contactHeader);
			this.inviteTid = st;
			this.inviteRequest = request;

			sendInviteOK();

			System.out.println("processInvite method success");
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}

	private void sendInviteOK() {
		try {
			if (inviteTid.getState() != TransactionState.COMPLETED) {
				System.out.println("Igneel: Dialog state before 200: " + inviteTid.getDialog().getState());
				inviteTid.sendResponse(okResponse);
				System.out.println("Igneel: Dialog state after 200: " + inviteTid.getDialog().getState());
			}
			messageProcessor.processInfo("Igneel: process ack request");
		} catch (SipException ex) {
			ex.printStackTrace();
		} catch (InvalidArgumentException ex) {
			ex.printStackTrace();
		}
	}

	public void processBye() {
		try {
			System.out.println("bye process start");
			Request request = this.dialog.createRequest("BYE");
			ClientTransaction transaction = this.sipProvider.getNewClientTransaction(request);
			this.dialog.sendRequest(transaction);
			System.out.println("bye request success");
			messageProcessor.processInfo("BYE request send success");
		} catch (Exception ex) {
			System.out.println("ERROR: " + ex.getMessage());
		}
	}
}
