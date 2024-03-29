/*
 * @Author Fernando Gonzalez.
 */
package com.silocom.codec8.receiver;

import com.silocom.m2m.layer.physical.Connection;
import com.silocom.m2m.layer.physical.MessageListener;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

/**
 *
 * @author silocom01
 */
public class Receiver implements MessageListener {

    Connection con;
    private final byte[] imeiExpected;
    private final int imeiLength = 17;
    long timeout;
    static final int codec8 = 0x08;
    static final int codec8E = 0x8E;
    static final int codec12 = 0x0C;
    byte[] processingMsg = new byte[0];

    private int expectedMessage = -1;

    private final Object SYNC = new Object();

    private CodecReport answer;

    private CodecListener listener;

    public Receiver(Connection con, byte[] imeiExpected, long timeout) {
        this.con = con;
        this.imeiExpected = imeiExpected;
        this.timeout = timeout;
    }

    public void setListener(CodecListener listener) {
        this.listener = listener;
    }

    @Override
    public void receiveMessage(byte[] message) {
        // System.out.println("Message received");
        if (message.length == imeiLength) {
            // System.out.println("IMEI message: " + Utils.hexToString(message));

            byte[] imeiReceived = new byte[15];

            System.arraycopy(message, 2, imeiReceived, 0, imeiReceived.length);

            if (Arrays.equals(imeiReceived, imeiExpected)) {
                // System.out.println("IMEI equals");
                byte[] accept = new byte[]{0x01};   //acepta el IMEI
                con.sendMessage(accept);
            }

        } else {

            try {
                processingMsg = Utils.concatByteArray(processingMsg, message);

                if (procMsg(processingMsg)) {
                    processingMsg = new byte[0];
                }
            } catch (Exception e) {
                processingMsg = new byte[0];
                e.printStackTrace();
            }

        }
    }

    @Override
    public void receiveMessage(byte[] message, Connection con) {

        if (message.length == imeiLength) {
            byte[] imeiReceived = new byte[15];

            System.arraycopy(message, 2, imeiReceived, 0, imeiReceived.length);

            if (Arrays.equals(imeiReceived, imeiExpected)) {  //Es el IMEI esperado?
                this.con = con;  //si es IMEI
                con.addListener(this);
                //Send 0x01 to the device
                byte[] accept = new byte[]{0x01};
                con.sendMessage(accept);         //Envio accept al equipo si es el IMEI
            } else {
                byte[] deny = new byte[]{0x00};
                con.sendMessage(deny);         //envio deny al equipo si no es el IMEI
            }

        }
    }

    private boolean procMsg(byte[] message) {

        byte[] dfLength = new byte[4];
        dfLength[0] = message[4];
        dfLength[1] = message[5];
        dfLength[2] = message[6];
        dfLength[3] = message[7];

        int dataFieldLength = ByteBuffer.wrap(dfLength).getInt();
        int messageType = message[8] & 0xFF;  //en el byte 8 del mensaje que no es IMEI, se encuentra el tipo de protocolo utilizado

        switch (messageType) {

            case codec8:

                if (dataFieldLength > message.length) {
                    System.out.println("dataF minor than message length");
                    return false;
                }
                
                System.out.println("dataF major than message length");
                byte[] crc16Codec8Parsed = new byte[4];
                System.arraycopy(message, message.length - 4, crc16Codec8Parsed, 0, 4);

                byte[] CRC16Codec8_Calculated = CRC16.calcCRC16(Arrays.copyOfRange(message, 8, message.length - 4));

                System.out.println("crc parsed " + Utils.hexToString(crc16Codec8Parsed));
                System.out.println("crc calc " + Utils.hexToString(CRC16Codec8_Calculated));

                if (!Arrays.equals(CRC16Codec8_Calculated, crc16Codec8Parsed)) {
                    System.out.println("CRC not equal");
                    return true;
                }
                System.out.println("CRC equal");
                System.out.println("AVL data copy of range");
                byte[] AVLData = Arrays.copyOfRange(message, 10, message.length - 5);   //Todos los records

                List<CodecReport> reports = Parser.parserCodec8(AVLData); //Envio la data (puede ser 1 o mas records, maximo 255 records por paquete) a pasear al metodo parser 
                System.out.println("report list");
                byte[] NofData1 = new byte[4];
                NofData1[0] = 0x00;
                NofData1[1] = 0x00;
                NofData1[2] = 0x00;
                NofData1[3] = message[9];

                byte[] NofData2 = new byte[4];
                NofData2[0] = 0x00;
                NofData2[1] = 0x00;
                NofData2[2] = 0x00;
                NofData2[3] = message[message.length - 5];

                con.sendMessage(NofData1);

                if (listener != null) {
                    listener.onData(reports);
                }
                break;

            case codec8E:

                byte[] crc16Codec8EParsed = new byte[4];
                System.arraycopy(message, message.length - 4, crc16Codec8EParsed, 0, 4);

                byte[] CRC16Codec8E_Calculated = new byte[4];
                CRC16Codec8E_Calculated[0] = 0x00;
                CRC16Codec8E_Calculated[1] = 0x00;
                CRC16Codec8E_Calculated[2] = CRC16.calcCRC16(Arrays.copyOfRange(message, 8, message.length - 4))[0];
                CRC16Codec8E_Calculated[3] = CRC16.calcCRC16(Arrays.copyOfRange(message, 8, message.length - 4))[1];

                if (Arrays.equals(crc16Codec8EParsed, CRC16Codec8E_Calculated)) {

                    //TODO: Implementacion de codec8 Extended
                }

                break;

            case codec12:

                //separar el header y demas de la data
                byte[] codec12Data = Arrays.copyOfRange(message, 15, message.length - 5);

                byte[] toDecode = new byte[3];

                toDecode[0] = message[15];  //Primera letra del comando
                toDecode[1] = message[16];  //...
                toDecode[2] = message[17];  //...

                String decoded = new String(toDecode);
                /* verificar las tres primeras letras de cada mensaje para saber 
                 que tipo de comando es, se tomo un arreglo de 3 bytes para hacerlo estandar*/

                switch (decoded) {
                    case "GPS": //mensaje de getgps    0x475053
                        if (expectedMessage == 1) {
                            synchronized (SYNC) {
                                answer = Parser.codec12Parser_getgps(codec12Data);
                                SYNC.notifyAll();
                            }
                        }
                        break;

                    case "DI1": //mensaje de getio    0x444931
                        if (expectedMessage == 2) {
                            synchronized (SYNC) {
                                answer = Parser.codec12Parser_getio(codec12Data);
                                SYNC.notifyAll();
                            }
                        }
                        break;

                    case "DOU": {
                        if (expectedMessage == 4) {
                            synchronized (SYNC) {

                                SYNC.notifyAll();
                            }
                        }
                        break;
                    }
                    case "Bat": //mensaje de battery
                        if (expectedMessage == 3) {
                            synchronized (SYNC) {
                                answer = Parser.codec12Parser_battery(codec12Data);
                                SYNC.notifyAll();
                            }
                        }
                        break;
                }

                break;

        }

        return true;
    }

    public CodecReport sendMessage(byte[] toSend, int expected) {
        answer = null;
        expectedMessage = expected;
        con.sendMessage(toSend);

        if (answer == null) {
            synchronized (SYNC) {
                try {
                    SYNC.wait(timeout);
                } catch (InterruptedException ex) {
                }
            }
        }
        expectedMessage = -1;
        return answer;

    }
}
