package edu.buffalo.cse.cse486586.groupmessenger2;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.StreamCorruptedException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * GroupMessengerActivity is the main Activity for the assignment.
 * 
 * @author stevko
 *
 */

class Message implements Serializable {
    private boolean isFinal;
    private int seq_no;
    private int proposed_seq;
    private int agreed_seq;
    private int port_no;
    private String message;

    public Message() { }
    public Message(int seq, int port, String msg) {
        this.isFinal = false;
        this.seq_no = seq;
        this.proposed_seq = -1;
        this.agreed_seq = -1;
        this.port_no = port;
        this.message = msg;
    }

    public Message(Message m) {
        this.isFinal = m.isFinal;
        this.seq_no = m.seq_no;
        this.proposed_seq = m.proposed_seq;
        this.agreed_seq = m.agreed_seq;
        this.port_no = m.port_no;
        this.message = m.message;
    }

    public boolean getIsFinal() { return isFinal; }
    public int getSeq_no() { return seq_no; }
    public int getProposed_seq() { return proposed_seq; }
    public int getAgreed_seq() { return agreed_seq; }
    public int getPort_no() { return port_no; }
    public String getMessage() { return message; }

    public void setIsFinal(boolean isfinal) { this.isFinal = isfinal; }
    public void setProposed_seq(int seq) { this.proposed_seq = seq; }
    public void setAgreed_seq(int seq) { this.agreed_seq = seq; }
    public void setPort_no(int port) { this.port_no = port; }
}

public class GroupMessengerActivity extends Activity {
    int sequence = 0;
    static int port_id;
    static int seqNo = 0;

    static final int SERVER_PORT = 10000;
    static final int[] device_list = {5554, 5556, 5558, 5560, 5562};
    static final String TAG = GroupMessengerActivity.class.getSimpleName();
    static final String[] REMOTE_PORT = {"11108", "11112", "11116", "11120", "11124"};

    static PriorityQueue<Message> priority_queue = new PriorityQueue<Message>(20);
    static SparseIntArray msgSeq = new SparseIntArray();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_messenger);
        TelephonyManager tel = (TelephonyManager)this.getSystemService(Context.TELEPHONY_SERVICE);
        String portStr = tel.getLine1Number().substring(tel.getLine1Number().length() - 4);
        final String myPort = String.valueOf((Integer.parseInt(portStr) * 2));

        port_id = Integer.parseInt(portStr);
        for (int i = 0; i < device_list.length; i++) msgSeq.put(device_list[i], 0);
        try {
            ServerSocket serverSocket = new ServerSocket(SERVER_PORT);
            new ServerTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, serverSocket);
        }
        catch (IOException e) {
            Log.e(TAG, "Can't create a ServerSocket");
            return;
        }

        /*
         * TODO: Use the TextView to display your messages. Though there is no grading component
         * on how you display the messages, if you implement it, it'll make your debugging easier.
         */
        TextView tv = (TextView) findViewById(R.id.textView1);
        tv.setMovementMethod(new ScrollingMovementMethod());
        
        /*
         * Registers OnPTestClickListener for "button1" in the layout, which is the "PTest" button.
         * OnPTestClickListener demonstrates how to access a ContentProvider.
         */
        findViewById(R.id.button1).setOnClickListener(
                new OnPTestClickListener(tv, getContentResolver()));
        
        /*
         * TODO: You need to register and implement an OnClickListener for the "Send" button.
         * In your implementation you need to get the message from the input box (EditText)
         * and send it to other AVDs.
         */
        final EditText editText = (EditText) findViewById(R.id.editText1);
        editText.setOnKeyListener(
            new View.OnKeyListener(){
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    if ((event.getAction() == KeyEvent.ACTION_DOWN) && (keyCode == KeyEvent.KEYCODE_ENTER)) {
                        String msg = editText.getText().toString() + "\n";
                        editText.setText("");
                        new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, msg, myPort);
                        return true;
                    }
                    return false;
                }
            }
        );

        findViewById(R.id.button4).setOnClickListener(
            new View.OnClickListener() {
                public void onClick(View v) {
                    String msg = editText.getText().toString() + "\n";
                    editText.setText("");
                    new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, msg, myPort);
                }
            }
        );
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.activity_group_messenger, menu);
        return true;
    }

    private class ServerTask extends AsyncTask<ServerSocket, String, Void> {
        private final Uri mUri = buildUri("content", "edu.buffalo.cse.cse486586.groupmessenger2.provider");
        private final ContentResolver mContentResolver = getContentResolver();

        private Uri buildUri(String scheme, String authority) {
            Uri.Builder uriBuilder = new Uri.Builder();
            uriBuilder.authority(authority);
            uriBuilder.scheme(scheme);
            return uriBuilder.build();
        }

        @Override
        protected Void doInBackground(ServerSocket... sockets) {
            int port = 0;
            int proposal;
            Message message;
            Message temp;
            Iterator<Message> iter;
            Log.println(Log.INFO, TAG, "Server started");

            while(true) {
                try {
                    Socket socket = sockets[0].accept();
                    ObjectInputStream in_stream = new ObjectInputStream(socket.getInputStream());
                    ObjectOutputStream out_stream = new ObjectOutputStream(socket.getOutputStream());
                    message = (Message)in_stream.readObject();
                    port = message.getPort_no();
                    proposal = msgSeq.get(port) + 1;
                    Log.println(Log.INFO, TAG, "Message received by server: " + message.getMessage());

                    if(proposal > message.getSeq_no()){
                        message.setPort_no(port_id);
                        message.setProposed_seq(proposal);
                    }
                    message.setIsFinal(false);
                    priority_queue.add(message);
                    out_stream.writeObject(message);
                    out_stream.flush();
                    Log.println(Log.INFO, TAG, "Proposal: " + proposal);

                    message = (Message)in_stream.readObject();
                    iter = priority_queue.iterator();
                    while(iter.hasNext()){
                        temp = iter.next();
                        Log.println(Log.INFO, TAG, "Agreed message from failed server!");
                        if(temp.getSeq_no() == message.getSeq_no() && temp.getPort_no() == message.getPort_no()){
                            priority_queue.remove(temp);
                            message.setIsFinal(true);
                            priority_queue.add(message);
                            break;
                        }
                    }

                    while(priority_queue.peek() != null && priority_queue.peek().getIsFinal()){
                        int seq = sequence++;
                        message = priority_queue.poll();
                        Log.println(Log.INFO, TAG, "Agreed message of correct priority!");
                        ContentValues cv = new ContentValues();
                        cv.put("key", Integer.toString(seq));
                        cv.put("value", message.getMessage());
                        mContentResolver.insert(mUri, cv);
                    }
                    publishProgress(sequence + ":" + message.getMessage());
                }
                catch (EOFException e){ Log.e(TAG, "Server EOFException: " + e); }
                catch (StreamCorruptedException e){ Log.e(TAG, "Server StreamCorruptedException: " + e); }
                catch (SocketTimeoutException e){ Log.e(TAG, "Server SocketTimeoutException: " + e); }
                catch (IOException e) { Log.e(TAG, "Server IOException: " + e); }
                catch (Exception e){ Log.e(TAG,"Server Exception: " + e); }

                finally{
                    Iterator<Message> iter1 = priority_queue.iterator();
                    while(iter1.hasNext()){
                        Message del_msg = iter1.next();
                        if(del_msg.getPort_no() == port) priority_queue.remove(del_msg);
                    }
                }
            }
        }

        @Override
        protected void onProgressUpdate(String... strings) {
            TextView remoteTextView = (TextView) findViewById(R.id.textView1);
            remoteTextView.append(strings[0].trim() + "\t\n");
            return;
        }
    }

    private class ClientTask extends AsyncTask<String, Void, Void> {
        @Override
        protected Void doInBackground(String... msgs) {
            seqNo++;
            String msg_out;
            Message message;
            List<Message> messageList = new ArrayList<Message>();

            Socket socket;
            ObjectInputStream objectInputStream;
            ObjectOutputStream objectOutputStream;
            ObjectInputStream objectInputStreams[] = new ObjectInputStream[REMOTE_PORT.length];
            ObjectOutputStream objectOutputStreams[] = new ObjectOutputStream[REMOTE_PORT.length];

            for(int i = 0; i < REMOTE_PORT.length; i++){
                try {
                    socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(REMOTE_PORT[i]));
                    objectOutputStreams[i] = new ObjectOutputStream(socket.getOutputStream());
                    objectOutputStream = objectOutputStreams[i];
                    objectInputStreams[i] = new ObjectInputStream(socket.getInputStream());
                    objectInputStream = objectInputStreams[i];
                    socket.setSoTimeout(2000);

                    msg_out = msgs[0];
                    Log.println(Log.INFO, TAG, "Initial message by client: " + msg_out);
                    message = new Message(seqNo, port_id, msg_out);
                    objectOutputStream.writeObject(message);
                    objectOutputStream.flush();
                    message = (Message)objectInputStream.readObject();
                    messageList.add(message);
                    Log.println(Log.INFO, TAG, "Proposal received by client: " + message.getProposed_seq());
                }
                catch (UnknownHostException e) { Log.e(TAG, "Client1 UnknownHostException: " + e); }
                catch (EOFException e){ Log.e(TAG, "Client1 EOFException: " + e); }
                catch (StreamCorruptedException e){ Log.e(TAG, "Client1 StreamCorruptedException: " + e); }
                catch (SocketTimeoutException e){ Log.e(TAG, "Client1 SocketTimeoutException: " + e); }
                catch (IOException e) { Log.e(TAG, "Client1 IOException: " + e); }
                catch (Exception e){ Log.e(TAG,"Client1 Exception: " + e); }
            }

            Message agreed_msg = new Message(messageList.get(0));
            int agreed_no = seqNo;
            for(int i = 0; i < messageList.size(); i++){
                Message m = messageList.get(i);
                if(m.getProposed_seq() > agreed_no){
                    agreed_no = m.getProposed_seq();
                    agreed_msg = m;
                }
            }
            Log.println(Log.INFO, TAG, "Agreed message by client: " + agreed_msg.getMessage() + " " + agreed_msg.getAgreed_seq());

            seqNo = agreed_no;
            agreed_msg.setAgreed_seq(agreed_no);
            for(int i = 0; i < REMOTE_PORT.length; i++){
                try{
                    socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}), Integer.parseInt(REMOTE_PORT[i]));
                    objectOutputStream = objectOutputStreams[i];
                    objectOutputStream.writeObject(agreed_msg);
                    objectOutputStream.flush();
                    socket.close();
                }
                catch (UnknownHostException e) { Log.e(TAG, "Client2 UnknownHostException: " + e); }
                catch (EOFException e){ Log.e(TAG, "Client2 EOFException: " + e); }
                catch (StreamCorruptedException e){ Log.e(TAG, "Client2 StreamCorruptedException: " + e); }
                catch (SocketTimeoutException e){ Log.e(TAG, "Client2 SocketTimeoutException: " + e); }
                catch (IOException e) { Log.e(TAG, "Client2 IOException: " + e); }
                catch (Exception e){ Log.e(TAG,"Client2 Exception: " + e); }
            }
            return null;
        }
    }
}
