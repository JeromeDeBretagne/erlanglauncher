package com.isotope11.erlanglauncher;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.system.Os;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.ericsson.otp.erlang.OtpErlangAtom;
import com.ericsson.otp.erlang.OtpErlangDecodeException;
import com.ericsson.otp.erlang.OtpErlangExit;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangPid;
import com.ericsson.otp.erlang.OtpErlangTuple;
import com.ericsson.otp.erlang.OtpMbox;
import com.ericsson.otp.erlang.OtpNode;

import com.ericsson.otp.erlang.OtpLocalSocketTransportFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

  private static Context context;
  private static String filesDir;

  // Subdirectory within the app assets where to drop the .beam file(s)
  private static final String erlangBeamDir = "ebin";

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    context = getApplicationContext();
    filesDir = context.getFilesDir().getAbsolutePath();

    setContentView(R.layout.activity_main);

    if (savedInstanceState == null) {
      getSupportFragmentManager().beginTransaction()
              .add(R.id.container, new PlaceholderFragment())
              .commit();
    }
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {

    // Inflate the menu; this adds items to the action bar if it is present
    getMenuInflater().inflate(R.menu.main, menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    // Handle action bar item clicks here. The action bar will
    // automatically handle clicks on the Home/Up button, so long
    // as you specify a parent activity in AndroidManifest.xml.
    int id = item.getItemId();
    if (id == R.id.action_settings) {
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  /**
   * A placeholder fragment containing a simple view.
   */
  public static class PlaceholderFragment extends Fragment {
    TextView mHello;

    public PlaceholderFragment() {
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
      View rootView = inflater.inflate(R.layout.fragment_main, container, false);

      mHello = (TextView) rootView.findViewById(R.id.helloWorld);

      // Need to make this call optional, check if it's there, or something...
      createErlangRuntimeIntoDataDir();

      listFiles();
      copyErlangCode();
      spawnErlang(); // The command is not launching Epmd
      listProcesses();
      JinterfaceTester task = new JinterfaceTester();
      task.execute();

      mHello.setText("All good...");

      return rootView;
    }

    public void listFiles() {
      doCommand("/system/bin/ls -al " + filesDir + "/erlang/erts-16.2/bin");
    }

    public void listProcesses() {
      doCommand("/system/bin/ps");
    }

    public void doCommand(String command) {
      doCommand(command, null, null, true);
    }

    public void doCommand(String command, String[] envp, File dir, boolean wait) {
      try {
        // Executes the command.
        Process process = Runtime.getRuntime().exec(command, envp, dir);

        // Reads stdout.
        // NOTE: You can write to stdin of the command using
        //       process.getOutputStream().
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()));

        int read;
        char[] buffer = new char[4096];
        StringBuffer output = new StringBuffer();
        while ((read = reader.read(buffer)) > 0) {
          output.append(buffer, 0, read);
        }
        reader.close();

        if (wait) {
          // Waits for the command to finish.
          process.waitFor();
        }

        // Reads stderr.
        reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream()));

        StringBuffer error = new StringBuffer();
        while ((read = reader.read(buffer)) > 0) {
          error.append(buffer, 0, read);
        }
        reader.close();

        // Send stdout and stderr to the log
        if (output.length() != 0) {
          Log.d("Fragment", output.toString());
        }
        if (error.length() != 0) {
          Log.d("Fragment error", error.toString());
        }

      } catch (IOException e) {
        throw new RuntimeException(e);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }

    // Spawn the 'erl' command into a new thread so that it doesn't block
    // the execution of the rest of the Java program
    public void spawnErlang() {
      new Thread(() -> {
        try {

          Log.d("Fragment", "spawnErlang");

          // Use ProcessBuilder to merge stdout and stderr
          ProcessBuilder pb = new ProcessBuilder(
                   "erlang/bin/erl",
                   // The '-sname node1@localhost' argument could be used instead
                   // or '-sname node1' otherwise. Using the 127.0.0.1 IP address
                   // for the host part guarantees that DNS lookup won't be used.
                   "-name", "node1@127.0.0.1",
                   // The directory(ies) where to search for .beam module files,
                   // in this case in 'ebin'.
                   "-pa", erlangBeamDir,
                   // Use a distribution protocol based on Unix Domain Sockets
                   "-proto_dist", "erl_uds",
                   // So no need to launch the Erlang Port Mapper Daemon (Epmd)
                   "-no_epmd",
                   // The "cookie" shared among nodes
                   "-setcookie", "cookie",
                   // The name of the Erlang module containing the default 'start'
                   // function to run.
                   "-run", "hello_jinterface"
          );
          pb.redirectErrorStream(true);

          // Pass the environment variables
          Map<String, String> env = pb.environment();
          // The HOME environment variable must be set for the Erlang node to
          // launch, otherwise the launch would fail with the following message:
          //    'error:: erlexec: HOME must be set'
          // with previous versions of the Erlang runtime. This was fixed in
          // Erlang 23 as described here: https://bugs.erlang.org/browse/ERL-476
          //
          // Recent versions of the 'erl' script in newer Erlang releases have a
          // built-in mechanism to dynamically find the 'ROOT_DIR', introduced in
          // commit: https://github.com/erlang/otp/commit/5ea1d07
          // However, this implementation doesn't work with the very specific way
          // of bundling and extracting the Erlang runtime and executables into
          // the native lib/<abi> directory on Android. Indeed, the usual Erlang
          // runtime file stucture is then fully recreated using symlinks which
          // is simply not expected and so not supported by 'dyn_erl --realpath'.
          //
          // Pass the ERL_ROOTDIR environment variable instead to set dynamically
          // the absolute path of the Erlang Runtime which is configured in a
          // different location for multiple users on Android. The change has
          // been discussed here: https://github.com/erlang/otp/pull/2863
          env.put("HOME", filesDir);
          env.put("ERL_ROOTDIR", filesDir + "/erlang");

          // The working directory to use when launching the command
          pb.directory(new File(filesDir + "/"));

          Process process = pb.start();

          // Open a reader for both stdout and stderr outputs
          BufferedReader reader = new BufferedReader(
                  new InputStreamReader(process.getInputStream()));

          // Read line-by-line and send the output to Logcat
          String line;
          while ((line = reader.readLine()) != null) {
            Log.d("Fragment", line);
          }
          reader.close();

          // Waits for the command to finish.
          process.waitFor();

        } catch (IOException e) {
          throw new RuntimeException(e);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }).start();
    }

    protected void createErlangRuntimeIntoDataDir() {
      Log.d("Fragment", "createErlangRuntimeIntoDataDir start");

      ApplicationInfo info = context.getApplicationInfo();

      File filesMappingFile = new File(info.nativeLibraryDir, "libmappings.so");
      if (!filesMappingFile.exists()) {
        Log.e("Fragment", "No file mapping at " +
              filesMappingFile.getAbsolutePath());
        return;
      }

      Log.d("Fragment", "Create the Erlang Runtime file structure");
      try {
        BufferedReader reader =
          new BufferedReader(new FileReader(filesMappingFile));
        String line;
        while ((line = reader.readLine()) != null) {
          String[] parts = line.split("←");
          if (parts.length != 2) {
            Log.e("Fragment", "Malformed line " + line + " in " +
                  filesMappingFile.getAbsolutePath());
            continue;
          }

          String oldPath = info.nativeLibraryDir + "/" + parts[0];
          String newPath = filesDir + "/" + parts[1];

          File directory = new File(newPath).getParentFile();
          if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new RuntimeException("Unable to create directory: " +
                                       directory.getAbsolutePath());
          }

          Log.d("Fragment", "About to setup link: " + oldPath + " ← " + newPath);
          new File(newPath).delete();
          createSymLink(oldPath, newPath);
        }
      } catch (Exception e) {
        throw new RuntimeException(e);
      }

      Log.d("Fragment", "createErlangRuntimeIntoDataDir done");
    }

    protected void copyErlangCode() {
      AssetManager assets = context.getAssets();
      InputStream erlangCodeInputStream;
      FileOutputStream out;

      // Subdirectory within the app data where to copy the .beam file(s)
      File directory = new File(filesDir + "/" + erlangBeamDir);
      if (!directory.isDirectory() && !directory.mkdirs()) {
          throw new RuntimeException("Unable to create directory: " +
                                     directory.getAbsolutePath());
      }

      try {
        // Copy all the files from this asset subdirectory
        String[] fileList = assets.list(erlangBeamDir);
        for (String file : fileList) {
          erlangCodeInputStream = assets.open(erlangBeamDir + "/" + file);
          out = new FileOutputStream(directory + "/" + file);
          int read;
          byte[] buffer = new byte[8192];
          while ((read = erlangCodeInputStream.read(buffer)) > 0) {
            out.write(buffer, 0, read);
          }
          erlangCodeInputStream.close();
          out.flush();
          out.close();
        }
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  // Support the creation of symlinks before Android 5.0 Lollipop
  static void createSymLink(String originalFilePath, String newPath) {
    try {
      if (Build.VERSION.SDK_INT >= 21) { // VERSION_CODES.LOLLIPOP
        Os.symlink(originalFilePath, newPath);
        return;
      }
      final Class<?> libcore = Class.forName("libcore.io.Libcore");
      final java.lang.reflect.Field fOs = libcore.getDeclaredField("os");
      fOs.setAccessible(true);
      final Object os = fOs.get(null);
      final java.lang.reflect.Method method =
        os.getClass().getMethod("symlink", String.class, String.class);
      method.invoke(os, originalFilePath, newPath);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }


  public static class JinterfaceTester extends AsyncTask<Object, Void, String>{
    @Override
    protected String doInBackground(Object... arg0) {
      testJinterface();
      return "k...";
    }

    public void testJinterface(){
      // On the Jinterface side too, using the 127.0.0.1 IP address for the
      // host part guarantees that DNS lookup won't be used.

      // Name of the Erlang node launched previously and running locally
      String erlangNodeName = "node1@127.0.0.1"; // Or "node1@localhost"

      // Name of the Java nodes created using the Jinterface Java library
      String javaNodeName   = "node2@127.0.0.1"; // Or "node2@localhost
      String otherNodeName  = "node3@127.0.0.1"; // Or "node3@localhost"

      OtpNode javaNode  = null;
      OtpNode otherNode = null;
      OtpMbox mbox      = null;
      OtpErlangPid pid  = null;

      try {
        // Use stream Unix Domain Sockets (with the Android-specific
        // LocalSocket-based factory) as an alternative distribution protocol.
        OtpLocalSocketTransportFactory udsTransportFactory =
          new OtpLocalSocketTransportFactory(filesDir + "/");

        // Create a second node
        javaNode = new OtpNode(javaNodeName,
                               // The "cookie" shared among nodes
                               "cookie",
                               // Use the alternative distribution protocol
                               udsTransportFactory);

        // Create a third node
        otherNode = new OtpNode(otherNodeName,
                                // The "cookie" shared among nodes
                                "cookie",
                               // Use the alternative distribution protocol
                               udsTransportFactory);

        // Check the connection between the 2 nodes on the Jinterface side
        if (javaNode.ping(otherNodeName, 2000)) {
          System.out.println("The Jinterface nodes are connected");
        } else {
          System.out.println("The Jinterface nodes couldn't connect");
        }

        // Create a "mailbox" used to exchange messages with other nodes
        mbox = javaNode.createMbox();

        // Get the process identifier (or pid) of this mailbox
        pid = mbox.self();

        // Check if the Erlang node is alive, and setup a connection with it
        int timeout = 0;
        boolean pingSuccess = false;
        // Try every 500ms during 10s to let the Erlang node finish launching
        do {
            pingSuccess = javaNode.ping(erlangNodeName, 500);
            timeout += 500;
        } while (!pingSuccess && timeout < 10000);
        if (pingSuccess) {
          System.out.println("The Erlang node is up");
        } else {
          System.out.println("The Erlang node is not up");
          javaNode.close();
          otherNode.close();
          return;
        }
      } catch (IOException e1) {
        e1.printStackTrace();
      }

      // Create the following message: {pid, 'ping'}
      OtpErlangObject[] msg = {pid, new OtpErlangAtom("ping")};
      OtpErlangTuple tuple = new OtpErlangTuple(msg);

      // Pass this message to the process named 'pong' on the Erlang node
      mbox.send("pong", erlangNodeName, tuple);

      // Then try to receive the message sent back as a response...
      while (true) {
        try {
          // ...expected with the format: {pid of the sender, response}
          OtpErlangObject robj  = mbox.receive();
          OtpErlangTuple rtuple = (OtpErlangTuple) robj;
          OtpErlangPid fromPid  = (OtpErlangPid) (rtuple.elementAt(0));
          OtpErlangObject rmsg  = rtuple.elementAt(1);
          System.out.println("Message: " + rmsg + " received from: "
                  + fromPid.toString());

          // Finally send the 'stop' message to finish the exchange
          OtpErlangAtom stop = new OtpErlangAtom("stop");
          mbox.send(fromPid, stop);
          break;

        } catch (OtpErlangExit e) {
          e.printStackTrace();
          break;
        } catch (OtpErlangDecodeException e) {
          e.printStackTrace();
        }
      }

      javaNode.close();
      otherNode.close();
    }

  }
}
