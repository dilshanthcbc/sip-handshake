package sipclient;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Hashtable;
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
import javax.sip.TransactionTerminatedEvent;
import javax.sip.TransportNotSupportedException;
import javax.sip.address.Address;
import javax.sip.address.AddressFactory;
import javax.sip.address.SipURI;
import javax.sip.address.URI;
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

	private String to;
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
	
	private Request ackRequest;
	
	private ServerTransaction serverTransactionId;
	private Dialog callerDialog;
	private MessageFactory msgFactory;
	private static Hashtable<URI, URI> currUser = new Hashtable();
	ClientTransaction clientTransactionId;

	/** Here we initialize the SIP stack. */
	public SipLayer(String username, String ip, int port) throws PeerUnavailableException,
			TransportNotSupportedException, InvalidArgumentException, ObjectInUseException, TooManyListenersException {
		setUsername(username);
		sipFactory = SipFactory.getInstance();
		sipFactory.setPathName("gov.nist");
		Properties properties = new Properties();
		properties.setProperty("javax.sip.STACK_NAME", "Natsu");
		properties.setProperty("javax.sip.IP_ADDRESS", ip);

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
		this.to = to;
		SipURI from = addressFactory.createSipURI(getUsername(), getHost() + ":" + getPort());
		Address fromNameAddress = addressFactory.createAddress(from);
		fromNameAddress.setDisplayName(getUsername());
		FromHeader fromHeader = headerFactory.createFromHeader(fromNameAddress, "Natsu");

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

	public void processResponse(ResponseEvent responseEvent) {
		Response response = responseEvent.getResponse();
		int status = response.getStatusCode();
		System.out.println("response status: " + status);

		ClientTransaction clientTransactionId = responseEvent.getClientTransaction();
		try {
			messageProcessor.processInfo("Ringing...: " + status);
			if (status == Response.TRYING) {
				sendMessage(to, "HI");
			} else if (status == Response.RINGING) {
				processInviteResponse(responseEvent);
			} else if (status == Response.OK) {
				processInviteResponse(responseEvent);
			} else {
//				processBye(responseEvent, clientTransactionId);
			}
		} catch (Exception ex) {
			messageProcessor.processInfo("Error: " + ex.getMessage());
		}
	}

	public void processRequest(RequestEvent requestEvent) {
		Request request = requestEvent.getRequest();
		String methodName = request.getMethod();
		System.out.println("Method called: " + methodName);
		ServerTransaction serverTransactionId = requestEvent.getServerTransaction();
		if (request.getMethod().equals(Request.BYE)) {
			System.out.println("Bye request recieved");
			processBye(requestEvent, serverTransactionId);
		} 
	}

	public void processTimeout(TimeoutEvent evt) {
		messageProcessor.processError("Previous message not sent: " + "timeout");
	}

	public void processIOException(IOExceptionEvent evt) {
		messageProcessor.processError("Previous message not sent: " + "I/O Exception");
	}

	public void processDialogTerminated(DialogTerminatedEvent evt) {
	}

	public void processTransactionTerminated(TransactionTerminatedEvent evt) {
	}

	public String getHost() {
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

//	public void processBye(ResponseEvent responseEvent, ClientTransaction clientTransactionId) {
//		SipProvider sipProvider = (SipProvider) responseEvent.getSource();
//		Response response = responseEvent.getResponse();
//		Dialog dialog = responseEvent.getDialog();
//		try {
//			System.out.println("natsu:  got a bye sending OK.");
//			messageProcessor.processInfo("BYE state processed");
//		} catch (Exception ex) {
//			ex.printStackTrace();
//			System.exit(0);
//
//		}
//	}
	
	public void processBye(RequestEvent requestEvent, ServerTransaction serverTransactionId) {
		SipProvider sipProvider = (SipProvider) requestEvent.getSource();
		Request request = requestEvent.getRequest();
		Dialog dialog = requestEvent.getDialog();
		System.out.println("local party = " + dialog.getLocalParty());
		try {
			System.out.println("natsu:  got a bye sending OK.");
//			Response response = messageFactory.createResponse(200, request);
//			serverTransactionId.sendResponse(response);
			messageProcessor.processInfo("natsu BYE state processed");

		} catch (Exception ex) {
			ex.printStackTrace();
			System.exit(0);

		}
	}
	
	private void processInviteResponse(ResponseEvent responseReceivedEvent) throws SipException, InvalidArgumentException {
	    Response response = (Response) responseReceivedEvent.getResponse();
	    ClientTransaction tid = responseReceivedEvent.getClientTransaction();
	    CSeqHeader cseq = (CSeqHeader) response.getHeader(CSeqHeader.NAME);     
	    Dialog dialog = responseReceivedEvent.getDialog();

	    if (tid == null) {
	        if (ackRequest!=null && dialog!=null) {
	            System.out.println("re-sending ACK");
	            dialog.sendAck(ackRequest);
	        }
	        return;
	    }

	    if (response.getStatusCode() == Response.OK) {
	        System.out.println("Dialog after 200 OK  " + dialog);
	        System.out.println("Dialog State after 200 OK  " + dialog.getState());
	        ackRequest = dialog.createAck(cseq.getSeqNumber() );
	        dialog.sendAck(ackRequest);
	        System.out.println("Sending ACK success");
	    }
	}
}
