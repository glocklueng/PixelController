package com.neophob.sematrix.core.osc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.neophob.sematrix.core.api.CallbackMessage;
import com.neophob.sematrix.core.api.PixelController;
import com.neophob.sematrix.core.glue.FileUtils;
import com.neophob.sematrix.core.glue.impl.FileUtilsRemoteImpl;
import com.neophob.sematrix.core.properties.ValidCommands;
import com.neophob.sematrix.core.visual.OutputMapping;
import com.neophob.sematrix.core.visual.color.ColorSet;
import com.neophob.sematrix.osc.client.OscClientException;
import com.neophob.sematrix.osc.client.PixOscClient;
import com.neophob.sematrix.osc.client.impl.OscClientFactory;
import com.neophob.sematrix.osc.model.OscMessage;

/**
 * the osc reply manager sends back gui information, encoded
 * as osc package.
 * 
 * @author michu
 *
 */
public class OscReplyManager extends CallbackMessage<ArrayList>{

	private static final Logger LOG = Logger.getLogger(OscReplyManager.class.getName());
	private static final int SEND_ERROR_THRESHOLD = 4;
	
	private PixelController pixelController;

	private PixOscClient oscClient;
	private FileUtils fileUtilRemote;
	
	private int sendError;

	
	public OscReplyManager(PixelController pixelController) {
		this.pixelController = pixelController;		
	}

	public void handleClientResponse(OscMessage oscIn, String[] msg) throws OscClientException {

		ValidCommands cmd = ValidCommands.valueOf(msg[0]);
		OscMessage reply = null;

		switch (cmd) {
		case GET_CONFIGURATION:				
			reply = new OscMessage(cmd.toString(), convertFromObject(pixelController.getConfig()));						
			break;

		case GET_MATRIXDATA:				
			reply = new OscMessage(cmd.toString(), convertFromObject(pixelController.getMatrix()));			
			break;

		case GET_VERSION:
			reply = new OscMessage(cmd.toString(), pixelController.getVersion());
			break;

		case GET_COLORSETS:
			reply = new OscMessage(cmd.toString(), convertFromObject((ArrayList<ColorSet>)pixelController.getColorSets()));
			break;

		case GET_OUTPUTMAPPING:
			reply = new OscMessage(cmd.toString(), convertFromObject((CopyOnWriteArrayList<OutputMapping>)pixelController.getAllOutputMappings()));
			break;

		case GET_OUTPUTBUFFER:
			reply = new OscMessage(cmd.toString(), convertFromObject(pixelController.getOutput()));
			break;

		case GET_GUISTATE:
			reply = new OscMessage(cmd.toString(), convertFromObject((ArrayList<String>)pixelController.getGuiState()));
			break;

		case GET_PRESETSETTINGS:
			reply = new OscMessage(cmd.toString(), convertFromObject(pixelController.getPresetService().getSelectedPresetSettings()));
			break;

		case GET_JMXSTATISTICS:
			reply = new OscMessage(cmd.toString(), convertFromObject(pixelController.getPixConStat()));
			break;
			
		case GET_FILELOCATION:
			reply = new OscMessage(cmd.toString(), convertFromObject(getLazyfileUtilsRemote()));
			break;

		case REGISTER_VISUALOBSERVER:
			pixelController.observeVisualState(this);
			//send back ack
			reply = new OscMessage(cmd.toString(), new byte[0]);
			sendError = 0;
			break;

		case UNREGISTER_VISUALOBSERVER:
			pixelController.stopObserveVisualState(this);
			reply = new OscMessage(cmd.toString(), new byte[0]);
			break;

		default:
			LOG.log(Level.INFO, cmd.toString()+" unknown command ignored");
			break;
		}

		if (reply!=null) {
			this.verifyOscClient(oscIn.getSocketAddress());
			this.oscClient.sendMessage(reply);
			LOG.log(Level.INFO, cmd.toString()+" reply size: "+reply.getMessageSize());			
		}
	}
	
	private synchronized void verifyOscClient(SocketAddress socket) throws OscClientException {
		InetSocketAddress remote = (InetSocketAddress)socket;
		boolean initNeeded = false;

		if (oscClient == null) {
			initNeeded = true;
		} else if (oscClient.getTargetIp() != remote.getAddress().getHostName()) {
			//TODO Verify port nr
			initNeeded = true;
		}

		if (initNeeded) {			
			//TODO make port configurable
			oscClient = OscClientFactory.createClientTcp(remote.getAddress().getHostName(), 
					9875, PixelControllerOscServer.REPLY_PACKET_BUFFERSIZE);			
		}
	}

	/**
	 * message from visual state, something changed. if a remote client is registered
	 * we send the update to the remote client.
	 * 
	 * @param guiState
	 */
	@Override
	public void handleMessage(ArrayList guiState) {
		if (oscClient!=null) {
			
			OscMessage reply = new OscMessage(ValidCommands.GET_GUISTATE.toString(),
					convertFromObject((ArrayList<String>)pixelController.getGuiState()));			
			try {
				LOG.log(Level.INFO, reply.getPattern()+" reply size: "+reply.getMessageSize());			
				this.oscClient.sendMessage(reply);
				sendError = 0;
			} catch (OscClientException e) {
				LOG.log(Level.SEVERE, "Failed to send observer message, error no: "+sendError, e);
				if (sendError++ > SEND_ERROR_THRESHOLD) {
					//disable remote observer after some errors
					pixelController.stopObserveVisualState(this);
					LOG.log(Level.SEVERE, "Disable remote observer");
				}
			}			
		}
	}
	
	private synchronized FileUtils getLazyfileUtilsRemote() {
		if (this.fileUtilRemote == null) {			
			this.fileUtilRemote = new FileUtilsRemoteImpl(
						this.pixelController.getFileUtils().findBlinkenFiles(),
						this.pixelController.getFileUtils().findImagesFiles()
					);
		}
		return this.fileUtilRemote;
	}
	
	private byte[] convertFromObject(Serializable s) {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		ObjectOutput out = null;
		try {
			out = new ObjectOutputStream(bos);   
			out.writeObject(s);
			return bos.toByteArray();
		} catch (IOException e) {
			LOG.log(Level.WARNING, "Failed to serializable object", e);
			return new byte[0];
		} finally {
			try {
				if (out != null) {
					out.close();
				}
			} catch (IOException ex) {
				// ignore close exception
			}
			try {
				bos.close();
			} catch (IOException ex) {
				// ignore close exception
			}
		}
	}	
}
