/**
 * Copyright (C) 2011-2014 Michael Vogt <michu@neophob.com>
 *
 * This file is part of PixelController.
 *
 * PixelController is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PixelController is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PixelController.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.neophob.sematrix.core.output;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.neophob.sematrix.core.properties.Configuration;
import com.neophob.sematrix.core.properties.ColorFormat;
import com.neophob.sematrix.core.properties.DeviceConfig;
import com.neophob.sematrix.core.resize.PixelControllerResize;
import com.neophob.sematrix.core.visual.MatrixData;

/**
 *
 * @author michu
 *
 */
public abstract class AbstractDmxDevice extends Output {

    private static final transient Logger LOG = Logger.getLogger(AbstractDmxDevice.class.getName());

    /** The display options, does the buffer needs to be flipped? rotated? */
    protected List<DeviceConfig> displayOptions;

    /** The output color format. */
    private List<ColorFormat> colorFormat;

    /** define how the panels are arranged */
    private List<Integer> panelOrder;

    /** The x size. */
    private int xResolution;

    /** The y size. */
    private int yResolution;

    // dmx specific settings
    protected int sequenceID;
    protected int pixelsPerUniverse;
    protected int nrOfUniverse; //how many universes do we need for one screen
    protected int firstUniverseId;
    protected InetAddress targetAdress;

    /** The initialized. */
    protected boolean initialized;

    /** flip each 2nd scanline? */
    protected boolean snakeCabeling;

    protected ArrayList<int[]> mappings = new ArrayList<int[]>();
    protected int[] mapping;

    private int nrOfScreens;

    /**
     *
     * @param outputDeviceEnum
     * @param ph
     * @param controller
     * @param bpp
     */
    public AbstractDmxDevice(MatrixData matrixData, PixelControllerResize resizeHelper,
                             OutputDeviceEnum outputDeviceEnum, Configuration ph, int bpp,
                             int nrOfScreens) {
        super(matrixData, resizeHelper, outputDeviceEnum, ph, bpp);

        this.nrOfScreens = nrOfScreens;
        this.colorFormat = ph.getColorFormat();
        this.panelOrder = ph.getPanelOrder();
        this.xResolution = ph.parseOutputXResolution();
        this.yResolution = ph.parseOutputYResolution();
        this.snakeCabeling = ph.isOutputSnakeCabeling();
        this.mappings.add(ph.getOutputMappingValues(0));
        this.mappings.add(ph.getOutputMappingValues(1));
        this.mappings.add(ph.getOutputMappingValues(2));
        this.mappings.add(ph.getOutputMappingValues(3));

        this.initialized = false;
    }

    /**
     * concrete classes need to implement this
     *
     * @param universeId
     * @param buffer
     */
    protected abstract void sendBufferToReceiver(int universeId, byte[] buffer);

    /**
     *
     */
    protected void calculateNrOfUniverse() {
        // check how many universe we need
        this.nrOfUniverse = 1;
        int bufferSize = xResolution * yResolution;
        if (bufferSize > pixelsPerUniverse) {
            while (bufferSize > pixelsPerUniverse) {
                this.nrOfUniverse++;
                bufferSize -= pixelsPerUniverse;
            }
        }

        LOG.log(Level.INFO, "\tPixels per universe: " + pixelsPerUniverse);
        LOG.log(Level.INFO, "\tFirst universe ID: " + firstUniverseId);
        LOG.log(Level.INFO, "\t# of universe: " + nrOfUniverse * nrOfScreens);
        // LOG.log(Level.INFO, "\tOutput Mapping entry size: " + this.mapping.length);
        LOG.log(Level.INFO, "\tTarget address: " + targetAdress);
    }


    public byte[] concat(byte[] a, byte[] b) {
        int aLen = a.length;
        int bLen = b.length;
        byte[] c= new byte[aLen+bLen];
        System.arraycopy(a, 0, c, 0, aLen);
        System.arraycopy(b, 0, c, aLen, bLen);
        return c;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.neophob.sematrix.core.output.Output#update()
     */
    @Override
    public void update() {
        int universeOfs = 0;

        if (initialized) {
            for (int nr = 0; nr < nrOfScreens; nr++) {

                byte[] rgbBuffer = getScreenRGBBuffer(nr);

                //HACK to work around broken output on the pixlight
                int numUniverses = this.nrOfUniverse;
                if(nr == 0)
                {
                    byte[] buffer1 = getScreenRGBBuffer(1);
                    rgbBuffer = concat(rgbBuffer, buffer1);
                    numUniverses *= 2;
                }


                // send out
                //output 2 is broken, is appended to one
                int remainingBytes = rgbBuffer.length;
                int ofs = 0;
                for (int i = 0; i < numUniverses; i++) {
                    int tmp = pixelsPerUniverse * 3;
                    if (remainingBytes <= pixelsPerUniverse * 3) {
                        tmp = remainingBytes;
                    }
                    byte[] buffer = new byte[tmp];
                    System.arraycopy(rgbBuffer, ofs, buffer, 0, tmp);
                    remainingBytes -= tmp;
                    ofs += tmp;
                    final int targetUniverse = this.firstUniverseId + universeOfs;
                    sendBufferToReceiver(targetUniverse, buffer);

                    universeOfs++;
                }

            }
        }

    }

    private byte[] getScreenRGBBuffer(final int screenNr) {
        int panelNr = this.panelOrder.get(screenNr);

        // get buffer data
        int[] transformedBuffer = RotateBuffer.transformImage(super.getBufferForScreen(screenNr),
                displayOptions.get(panelNr), this.matrixData.getDeviceXSize(),
                this.matrixData.getDeviceYSize());

        if (this.snakeCabeling) {
            // flip each 2nd scanline
            transformedBuffer = OutputHelper.flipSecondScanline(transformedBuffer,
                    this.matrixData.getDeviceXSize(), this.matrixData.getDeviceYSize());
        } else if (this.mappings.get(screenNr).length > 0) {
            // do manual mapping
            transformedBuffer = OutputHelper.manualMapping(transformedBuffer, this.mappings.get(screenNr),
                    xResolution, yResolution);
        }

        byte[] rgbBuffer = OutputHelper.convertBufferTo24bit(transformedBuffer,
                colorFormat.get(panelNr));
        return rgbBuffer;
    }



    @Override
    public String getConnectionStatus() {
        if (initialized) {
            return "Target IP: " + targetAdress + ", # of universe: " + nrOfUniverse * nrOfScreens;
        }
        return "Not connected!";
    }

    @Override
    public boolean isSupportConnectionState() {
        return true;
    }

    @Override
    public boolean isConnected() {
        return initialized;
    }

    public int getPixelsPerUniverse() {
        return pixelsPerUniverse;
    }

    public int getNrOfUniverse() {
        return nrOfUniverse;
    }

    public int getFirstUniverseId() {
        return firstUniverseId;
    }

}
