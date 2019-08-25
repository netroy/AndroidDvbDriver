package info.martinmarinov.drivers.usb.rtl28xx;

import android.content.res.Resources;
import android.support.annotation.NonNull;

import java.util.Set;

import info.martinmarinov.drivers.DeliverySystem;
import info.martinmarinov.drivers.DvbCapabilities;
import info.martinmarinov.drivers.DvbException;
import info.martinmarinov.drivers.DvbStatus;
import info.martinmarinov.drivers.tools.SetUtils;
import info.martinmarinov.drivers.tools.SleepUtils;
import info.martinmarinov.drivers.usb.DvbFrontend;
import info.martinmarinov.drivers.usb.DvbTuner;

class CXD2837ER implements DvbFrontend {
    private final static int I2C_SLVX = 0;
    private final static int I2C_SLVT = 1;

    private final static int TS_SERIAL = 0x04;
    private final static int NO_AGCNEG = 0x40;
    private final static int TSBITS = 0x80;

    enum Xtal { XTAL_20500, XTAL_24000, XTAL_41000 }
    enum State { SHUTDOWN, SLEEP_S, ACTIVE_S, SLEEP_TC, ACTIVE_TC }
    private class ChipID {
        private final static int CXD2837ER = 0xb1;
        private final static int CXD2838ER = 0xb0;
        private final static int CXD2841ER = 0xa7;
        private final static int CXD2843ER = 0xa4;
        private final static int CXD2854ER = 0xc1;
    }

    private final static DvbCapabilities CAPABILITIES = new DvbCapabilities(
            42000000L,
            1002000000L,
            166667L,
            SetUtils.setOf(DeliverySystem.DVBT, DeliverySystem.DVBT2, DeliverySystem.DVBC));

    private final Rtl28xxDvbDevice.Rtl28xxI2cAdapter i2cAdapter;
    protected final Resources resources;
    private DvbTuner tuner;
    private Xtal xtal;
    private State state;

    private int i2cAddrSLVX;
    private int i2cAddrSLVT;
    private int flags;

    CXD2837ER(Rtl28xxDvbDevice.Rtl28xxI2cAdapter i2cAdapter, Resources resources) {
        this.i2cAdapter = i2cAdapter;
        this.resources = resources;
    }

    synchronized void write(int addressId, int reg, byte[] bytes) throws DvbException {
        write(addressId, reg, bytes, bytes.length);
    }

    synchronized void write(int addressId, int reg, byte[] value, int len) throws DvbException {
        // TODO: Implement
    }

    synchronized void writeReg(int addressId, int reg, int val) throws DvbException {
        // TODO: Implement
    }

    synchronized void read(int addressId, int reg, byte[] val, int len) throws DvbException {
        // TODO: Implement
    }

    synchronized int readReg(int addressId, int reg) throws DvbException {
        // TODO: Implement
        return 0;
    }

    synchronized void setRegBits(int addressId, int reg, int data, int mask) throws DvbException {
        if (mask != (byte)0xff) {
            int rdata = readReg(addressId, reg);
            if (rdata > 0) {
                return ;
            }
            data = ((data & mask) | (rdata & (mask ^ 0xFF)));
        }
        writeReg(addressId, reg, data);
    }

    private void initStats() {
        // TODO: is this needed????
    }

    private int getChipId() throws DvbException {
        writeReg(I2C_SLVT, 0, 0);
        int chipId = readReg(I2C_SLVT, 0xfd);
        if (chipId != 0)
            return chipId;
        writeReg(I2C_SLVX, 0, 0);
        return readReg(I2C_SLVX, 0xfd);
    }

    private void shutdownToSleepTC() throws DvbException {
        if (this.state != State.SHUTDOWN) {
            throw new DvbException(DvbException.ErrorCode.HARDWARE_EXCEPTION, "Tuner already active");
        }

        /* Set SLV-X Bank : 0x00 */
        writeReg(I2C_SLVX, 0x00, 0x00);
        /* Clear all demodulator registers */
        writeReg(I2C_SLVX, 0x02, 0x00);
        SleepUtils.usleep(3_000L);

        /* Set SLV-X Bank : 0x00 */
        writeReg(I2C_SLVX, 0x00, 0x00);
        /* Set demod SW reset */
        writeReg(I2C_SLVX, 0x10, 0x01);
        /* Select ADC clock mode */
        writeReg(I2C_SLVX, 0x13, 0x00);

        byte data = 0;
        switch (this.xtal) {
            case XTAL_20500:
                data = 0x0;
                break;
            case XTAL_24000:
                /* Select demod frequency */
                writeReg(I2C_SLVX, 0x12, 0x00);
                data = 0x03;
                break;
            case XTAL_41000:
                writeReg(I2C_SLVX, 0x12, 0x00);
                data = 0x01;
                break;
        }

        writeReg(I2C_SLVX, 0x14, data);
        /* Clear demod SW reset */
        writeReg(I2C_SLVX, 0x10, 0x00);
        SleepUtils.usleep(1_000L);

        /* Set SLV-T Bank : 0x00 */
        writeReg(I2C_SLVT, 0x00, 0x00);
        /* TADC Bias On */
        writeReg(I2C_SLVT, 0x43, 0x0a);
        writeReg(I2C_SLVT, 0x41, 0x0a);
        /* SADC Bias On */
        writeReg(I2C_SLVT, 0x63, 0x16);
        writeReg(I2C_SLVT, 0x65, 0x27);
        writeReg(I2C_SLVT, 0x69, 0x06);

        this.state = State.SLEEP_TC;
    }

    @Override
    public DvbCapabilities getCapabilities() {
        return CAPABILITIES;
    }

    @Override
    public void attatch() throws DvbException {
        int chipId = this.getChipId();
        switch (chipId) {
            case ChipID.CXD2837ER:
                break;
            default:
                throw new DvbException(DvbException.ErrorCode.DVB_DEVICE_UNSUPPORTED, "Only CXD2837ER currently supported");
        }
    }

    @Override
    public void release() {

    }

    @Override
    public void init(DvbTuner tuner) throws DvbException {
        this.tuner = tuner;
        this.shutdownToSleepTC();

        /* SONY_DEMOD_CONFIG_IFAGCNEG = 1 (0 for NO_AGCNEG */
        writeReg(I2C_SLVT, 0x00, 0x10);
        setRegBits(I2C_SLVT, 0xcb, ((this.flags & NO_AGCNEG) != 0 ? 0x00 : 0x40),0x40);
        /* SONY_DEMOD_CONFIG_IFAGC_ADC_FS = 0 */
        writeReg(I2C_SLVT, 0xcd, 0x50);
        /* SONY_DEMOD_CONFIG_PARALLEL_SEL = 1 */
        writeReg(I2C_SLVT, 0x00, 0x00);
        setRegBits(I2C_SLVT, 0xc4, ((this.flags & TS_SERIAL) != 0 ? 0x80 : 0x00), 0x80);

        /* clear TSCFG bits 3+4 */
        if ((this.flags & TSBITS) != 0)
            setRegBits(I2C_SLVT, 0xc4, 0x00, 0x18);

        this.initStats();
    }

    @Override
    public void setParams(long frequency, long bandwidthHz, @NonNull DeliverySystem deliverySystem) throws DvbException {
        if (deliverySystem != DeliverySystem.DVBT2) {
            throw new DvbException(DvbException.ErrorCode.DVB_DEVICE_UNSUPPORTED, "Only DVB-T2 supported currently");
        }
    }

    @Override
    public int readSnr() throws DvbException {
        return 0;
    }

    @Override
    public int readRfStrengthPercentage() throws DvbException {
        return 0;
    }

    @Override
    public int readBer() throws DvbException {
        return 0;
    }

    @Override
    public Set<DvbStatus> getStatus() throws DvbException {
        return null;
    }

    @Override
    public void setPids(int... pids) throws DvbException {

    }

    @Override
    public void disablePidFilter() throws DvbException {

    }
}
