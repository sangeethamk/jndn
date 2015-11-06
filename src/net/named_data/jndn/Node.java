/**
 * Copyright (C) 2014-2015 Regents of the University of California.
 * @author: Jeff Thompson <jefft0@remap.ucla.edu>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * A copy of the GNU Lesser General Public License is in the file COPYING.
 */

package net.named_data.jndn;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.named_data.jndn.encoding.ElementListener;
import net.named_data.jndn.encoding.EncodingException;
import net.named_data.jndn.encoding.TlvWireFormat;
import net.named_data.jndn.encoding.WireFormat;
import net.named_data.jndn.encoding.tlv.Tlv;
import net.named_data.jndn.encoding.tlv.TlvDecoder;
import net.named_data.jndn.impl.DelayedCallTable;
import net.named_data.jndn.impl.InterestFilterTable;
import net.named_data.jndn.impl.PendingInterestTable;
import net.named_data.jndn.impl.RegisteredPrefixTable;
import net.named_data.jndn.security.KeyChain;
import net.named_data.jndn.security.SecurityException;
import net.named_data.jndn.transport.Transport;
import net.named_data.jndn.util.Blob;
import net.named_data.jndn.util.CommandInterestGenerator;
import net.named_data.jndn.util.Common;

/**
 * The Node class implements internal functionality for the Face class.
 */
public class Node implements ElementListener {
  /**
   * Create a new Node for communication with an NDN hub with the given
   * Transport object and connectionInfo.
   * @param transport A Transport object used for communication.
   * @param connectionInfo A Transport.ConnectionInfo to be used to connect to
   * the transport.
   */
  public Node(Transport transport, Transport.ConnectionInfo connectionInfo)
  {
    transport_ = transport;
    connectionInfo_ = connectionInfo;
  }

  /**
   * Send the Interest through the transport, read the entire response and call
   * onData(interest, data).
   * @param pendingInterestId The getNextEntryId() for the pending interest ID
   * which Face got so it could return it to the caller.
   * @param interest The Interest to send.  This copies the Interest.
   * @param onData  This calls onData.onData when a matching data packet is
   * received.
   * @param onTimeout This calls onTimeout.onTimeout if the interest times out.
   * If onTimeout is null, this does not use it.
   * @param wireFormat A WireFormat object used to encode the message.
   * @param face The face which has the callLater method, used for interest
   * timeouts. The callLater method may be overridden in a subclass of Face.
   * @throws IOException For I/O error in sending the interest.
   * @throws Error If the encoded interest size exceeds getMaxNdnPacketSize().
   */
  public final void
  expressInterest
    (final long pendingInterestId, Interest interest, final OnData onData,
     final OnTimeout onTimeout, final WireFormat wireFormat, final Face face)
     throws IOException
  {
    final Interest interestCopy = new Interest(interest);

    if (connectStatus_ == ConnectStatus.CONNECT_COMPLETE) {
      // We are connected. Simply send the interest without synchronizing.
      expressInterestHelper
        (pendingInterestId, interestCopy, onData, onTimeout, wireFormat, face);
      return;
    }

    // Wile connecting, use onConnectedCallbacks_ to synchronize
    // onConnectedCallbacks_ as well as connectStatus_.
    synchronized(onConnectedCallbacks_) {
      // TODO: Properly check if we are already connected to the expected host.
      if (!transport_.isAsync()) {
        // The simple case: Just do a blocking connect and express.
        transport_.connect(connectionInfo_, this, null);
        expressInterestHelper
          (pendingInterestId, interestCopy, onData, onTimeout, wireFormat, face);
        // Make future calls to expressInterest send directly to the Transport.
        connectStatus_ = ConnectStatus.CONNECT_COMPLETE;

        return;
      }

      // Handle the async case.
      if (connectStatus_ == ConnectStatus.UNCONNECTED) {
        connectStatus_ = ConnectStatus.CONNECT_REQUESTED;

        // expressInterestHelper will be called by onConnected.
        onConnectedCallbacks_.add(new Runnable() {
          public void run() {
            try {
              expressInterestHelper
                (pendingInterestId, interestCopy, onData, onTimeout, wireFormat,
                 face);
            } catch (IOException ex) {
              logger_.log(Level.SEVERE, null, ex);
            }
          }
        });

        Runnable onConnected = new Runnable() {
          public void run() {
            // This is called on a separate thread from the surrounding code
            // when connected, so synchronize again.
            synchronized(onConnectedCallbacks_) {
              // Call each callback added while the connection was opening.
              for (int i = 0; i < onConnectedCallbacks_.size(); ++i)
                ((Runnable)onConnectedCallbacks_.get(i)).run();
              onConnectedCallbacks_.clear();

              // Make future calls to expressInterest send directly to the
              // Transport.
              connectStatus_ = ConnectStatus.CONNECT_COMPLETE;
            }
          }
        };
        transport_.connect(connectionInfo_, this, onConnected);
      }
      else if (connectStatus_ == ConnectStatus.CONNECT_REQUESTED) {
        // Still connecting. add to the interests to express by onConnected.
        onConnectedCallbacks_.add(new Runnable() {
          public void run() {
            try {
              expressInterestHelper
                (pendingInterestId, interestCopy, onData, onTimeout, wireFormat,
                 face);
            } catch (IOException ex) {
              logger_.log(Level.SEVERE, null, ex);
            }
          }
        });
      }
      else if (connectStatus_ == ConnectStatus.CONNECT_COMPLETE)
        // We have to repeat this check for CONNECT_COMPLETE in case the
        // onConnected callback was called while we were waiting to enter this
        // synchronized block.
        expressInterestHelper
          (pendingInterestId, interestCopy, onData, onTimeout, wireFormat, face);
      else
        // Don't expect this to happen.
        throw new Error
          ("Node: Unrecognized _connectStatus " + connectStatus_);
    }
  }

  /**
   * Remove the pending interest entry with the pendingInterestId from the
   * pending interest table. This does not affect another pending interest with
   * a different pendingInterestId, even if it has the same interest name.
   * If there is no entry with the pendingInterestId, do nothing.
   * @param pendingInterestId The ID returned from expressInterest.
   */
  public final void
  removePendingInterest(long pendingInterestId)
  {
    pendingInterestTable_.removePendingInterest(pendingInterestId);
  }

  /**
   * Append a timestamp component and a random value component to interest's
   * name. Then use the keyChain and certificateName to sign the interest. If
   * the interest lifetime is not set, this sets it.
   * @param interest The interest whose name is append with components.
   * @param keyChain The KeyChain object for signing interests.
   * @param certificateName The certificate name for signing interests.
   * @param wireFormat A WireFormat object used to encode the SignatureInfo and
   * to encode interest name for signing.
   * @throws SecurityException If cannot find the private key for the
   * certificateName.
   */
  void
  makeCommandInterest
    (Interest interest, KeyChain keyChain, Name certificateName,
     WireFormat wireFormat) throws SecurityException
  {
    commandInterestGenerator_.generate
      (interest, keyChain, certificateName, wireFormat);
  }

  /**
   * Register prefix with the connected NDN hub and call onInterest when a
   * matching interest is received. To register a prefix with NFD, you must
   * first call setCommandSigningInfo.
   * @param registeredPrefixId The getNextEntryId() for the registered prefix ID
   * which Face got so it could return it to the caller.
   * @param prefix A Name for the prefix to register. This copies the Name.
   * @param onInterest If not null, this creates an interest filter from prefix
   * so that when an Interest is received which matches the filter, this calls
   * onInterest.onInterest(prefix, interest, face, interestFilterId, filter).
   * If onInterest is null, it is ignored and you must call setInterestFilter.
   * @param onRegisterFailed This calls onRegisterFailed.onRegisterFailed(prefix)
   * if failed to retrieve the connected hub's ID or failed to register the
   * prefix.
   * @param onRegisterSuccess This calls
   * onRegisterSuccess.onRegisterSuccess(prefix, registeredPrefixId) when this
   * receives a success message from the forwarder. If onRegisterSuccess is null,
   * this does not use it.
   * @param flags The flags for finer control of which interests are forwarded
   * to the application.
   * @param wireFormat A WireFormat object used to encode the message.
   * @param commandKeyChain The KeyChain object for signing interests.
   * @param commandCertificateName The certificate name for signing interests.
   * @param face The face which is passed to the onInterest callback. If
   * onInterest is null, this is ignored.
   * @throws IOException For I/O error in sending the registration request.
   * @throws SecurityException If signing a command interest for NFD and cannot
   * find the private key for the certificateName.
   */
  public final void
  registerPrefix
    (long registeredPrefixId, Name prefix, OnInterestCallback onInterest,
     OnRegisterFailed onRegisterFailed, OnRegisterSuccess onRegisterSuccess,
     ForwardingFlags flags, WireFormat wireFormat, KeyChain commandKeyChain,
     Name commandCertificateName, Face face) throws IOException, SecurityException
  {
    nfdRegisterPrefix
      (registeredPrefixId, new Name(prefix), onInterest, onRegisterFailed,
       onRegisterSuccess, flags, commandKeyChain, commandCertificateName,
       wireFormat, face);
  }

  /**
   * Remove the registered prefix entry with the registeredPrefixId from the
   * registered prefix table. This does not affect another registered prefix with
   * a different registeredPrefixId, even if it has the same prefix name. If an
   * interest filter was automatically created by registerPrefix, also remove it.
   * If there is no entry with the registeredPrefixId, do nothing.
   * @param registeredPrefixId The ID returned from registerPrefix.
   */
  public final void
  removeRegisteredPrefix(long registeredPrefixId)
  {
    registeredPrefixTable_.removeRegisteredPrefix(registeredPrefixId);
  }

  /**
   * Add an entry to the local interest filter table to call the onInterest
   * callback for a matching incoming Interest. This method only modifies the
   * library's local callback table and does not register the prefix with the
   * forwarder. It will always succeed. To register a prefix with the forwarder,
   * use registerPrefix.
   * @param interestFilterId The getNextEntryId() for the interest filter ID
   * which Face got so it could return it to the caller.
   * @param filter The InterestFilter with a prefix and optional regex filter
   * used to match the name of an incoming Interest. This makes a copy of filter.
   * @param onInterest When an Interest is received which matches the filter,
   * this calls
   * onInterest.onInterest(prefix, interest, face, interestFilterId, filter).
   * @param face The face which is passed to the onInterest callback.
   */
  public final void
  setInterestFilter
    (long interestFilterId, InterestFilter filter, OnInterestCallback onInterest,
     Face face)
  {
    interestFilterTable_.setInterestFilter
      (interestFilterId, new InterestFilter(filter), onInterest, face);
  }

  /**
   * Remove the interest filter entry which has the interestFilterId from the
   * interest filter table. This does not affect another interest filter with
   * a different interestFilterId, even if it has the same prefix name.
   * If there is no entry with the interestFilterId, do nothing.
   * @param interestFilterId The ID returned from setInterestFilter.
   */
  public final void
  unsetInterestFilter(long interestFilterId)
  {
    interestFilterTable_.unsetInterestFilter(interestFilterId);
  }

  /**
   * The OnInterestCallback calls this to put a Data packet which
   * satisfies an Interest.
   * @param data The Data packet which satisfies the interest.
   * @param wireFormat A WireFormat object used to encode the Data packet.
   * @throws Error If the encoded Data packet size exceeds getMaxNdnPacketSize().
   */
  public final void
  putData(Data data, WireFormat wireFormat) throws IOException
  {
    Blob encoding = data.wireEncode(wireFormat);
    if (encoding.size() > getMaxNdnPacketSize())
      throw new Error
        ("The encoded Data packet size exceeds the maximum limit getMaxNdnPacketSize()");

    transport_.send(encoding.buf());
  }

  /**
   * Send the encoded packet out through the transport.
   * @param encoding The array of bytes for the encoded packet to send.  This
   * reads from position() to limit(), but does not change the position.
   * @throws Error If the encoded packet size exceeds getMaxNdnPacketSize().
   */
  public final void
  send(ByteBuffer encoding) throws IOException
  {
    if (encoding.remaining() > getMaxNdnPacketSize())
      throw new Error
        ("The encoded packet size exceeds the maximum limit getMaxNdnPacketSize()");

    transport_.send(encoding);
  }

  /**
   * Process any packets to receive and call callbacks such as onData,
   * onInterest or onTimeout. This returns immediately if there is no data to
   * receive. This blocks while calling the callbacks. You should repeatedly
   * call this from an event loop, with calls to sleep as needed so that the
   * loop doesn't use 100% of the CPU. Since processEvents modifies the pending
   * interest table, your application should make sure that it calls
   * processEvents in the same thread as expressInterest (which also modifies
   * the pending interest table).
   * This may throw an exception for reading data or in the callback for
   * processing the data. If you call this from an main event loop, you may want
   * to catch and log/disregard all exceptions.
   */
  public final void
  processEvents() throws IOException, EncodingException
  {
    transport_.processEvents();

    // If Face.callLater is overridden to use a different mechanism, then
    // processEvents is not needed to check for delayed calls.
    delayedCallTable_.callTimedOut();
  }

  public final Transport
  getTransport() { return transport_; }

  public final Transport.ConnectionInfo
  getConnectionInfo() { return connectionInfo_; }

  public final void onReceivedElement(ByteBuffer element) throws EncodingException
  {
    LocalControlHeader localControlHeader = null;
    if (element.get(0) == Tlv.LocalControlHeader_LocalControlHeader) {
      // Decode the LocalControlHeader and replace element with the payload.
      localControlHeader = new LocalControlHeader();
      localControlHeader.wireDecode(element, TlvWireFormat.get());
      element = localControlHeader.getPayloadWireEncoding().buf();
    }

    // First, decode as Interest or Data.
    Interest interest = null;
    Data data = null;
    if (element.get(0) == Tlv.Interest || element.get(0) == Tlv.Data) {
      TlvDecoder decoder = new TlvDecoder(element);
      if (decoder.peekType(Tlv.Interest, element.remaining())) {
        interest = new Interest();
        interest.wireDecode(element, TlvWireFormat.get());

        if (localControlHeader != null)
          interest.setLocalControlHeader(localControlHeader);
      }
      else if (decoder.peekType(Tlv.Data, element.remaining())) {
        data = new Data();
        data.wireDecode(element, TlvWireFormat.get());

        if (localControlHeader != null)
          data.setLocalControlHeader(localControlHeader);
      }
    }

    // Now process as Interest or Data.
    if (interest != null) {
      // Quickly lock and get all interest filter callbacks which match.
      ArrayList matchedFilters = new ArrayList();
      interestFilterTable_.getMatchedFilters(interest, matchedFilters);

      // The lock on interestFilterTable_ is released, so call the callbacks.
      for (int i = 0; i < matchedFilters.size(); ++i) {
        InterestFilterTable.Entry entry =
          (InterestFilterTable.Entry)matchedFilters.get(i);
        entry.getOnInterest().onInterest
         (entry.getFilter().getPrefix(), interest, entry.getFace(),
          entry.getInterestFilterId(), entry.getFilter());
      }
    }
    else if (data != null) {
      ArrayList pitEntries = new ArrayList();
      pendingInterestTable_.extractEntriesForExpressedInterest
        (data.getName(), pitEntries);
      for (int i = 0; i < pitEntries.size(); ++i) {
        PendingInterestTable.Entry pendingInterest =
          (PendingInterestTable.Entry)pitEntries.get(i);
        pendingInterest.getOnData().onData(pendingInterest.getInterest(), data);
      }
    }
  }

  /**
   * Check if the face is local based on the current connection through the
   * Transport; some Transport may cause network IO (e.g. an IP host name lookup).
   * @return True if the face is local, false if not.
   * @throws IOException
   */
  public final boolean isLocal() throws IOException{
    return transport_.isLocal(connectionInfo_);
  }

  /**
   * Shut down by closing the transport
   */
  public final void
  shutdown()
  {
    try {
      transport_.close();
    }
    catch (IOException e) {}
  }

  /**
   * Get the practical limit of the size of a network-layer packet. If a packet
   * is larger than this, the library or application MAY drop it.
   * @return The maximum NDN packet size.
   */
  public static int
  getMaxNdnPacketSize() { return Common.MAX_NDN_PACKET_SIZE; }

  /**
   * Call callback.run() after the given delay. This adds to
   * delayedCallTable_ which is used by processEvents().
   * @param delayMilliseconds The delay in milliseconds.
   * @param callback This calls callback.run() after the delay.
   */
  public final void
  callLater(double delayMilliseconds, Runnable callback)
  {
    delayedCallTable_.callLater(delayMilliseconds, callback);
  }

  /**
   * Get the next unique entry ID for the pending interest table, interest
   * filter table, etc. This uses a synchronized to be thread safe. Most entry
   * IDs are for the pending interest table (there usually are not many interest
   * filter table entries) so we use a common pool to only have to do the thread
   * safe lock in one method which is called by Face.
   * @return The next entry ID.
   */
  public long
  getNextEntryId()
  {
    synchronized(lastEntryIdLock_) {
      return ++lastEntryId_;
    }
  }

  /**
   * This is used in callLater for when the pending interest expires. If the
   * pendingInterest is still in the pendingInterestTable_, remove it and call
   * its onTimeout callback.
   * @param pendingInterest The pending interest to check.
   */
  private void
  processInterestTimeout(PendingInterestTable.Entry pendingInterest)
  {
    if (pendingInterestTable_.removeEntry(pendingInterest))
      pendingInterest.callTimeout();
  }

  /**
   * Do the work of expressInterest once we know we are connected. Add the entry
   * to the PIT, encode and send the interest.
   * @param pendingInterestId The getNextEntryId() for the pending interest ID
   * which Face got so it could return it to the caller.
   * @param interestCopy The Interest to send, which has already been copied by
   * expressInterest.
   * @param onData  This calls onData.onData when a matching data packet is
   * received.
   * @param onTimeout This calls onTimeout.onTimeout if the interest times out.
   * If onTimeout is null, this does not use it.
   * @param wireFormat A WireFormat object used to encode the message.
   * @param face The face which has the callLater method, used for interest
   * timeouts. The callLater method may be overridden in a subclass of Face.
   * @throws IOException For I/O error in sending the interest.
   * @throws Error If the encoded interest size exceeds getMaxNdnPacketSize().
   */
  private void
  expressInterestHelper
    (long pendingInterestId, Interest interestCopy, OnData onData,
     OnTimeout onTimeout, WireFormat wireFormat, Face face) throws IOException
  {
    final PendingInterestTable.Entry pendingInterest =
      pendingInterestTable_.add(pendingInterestId, interestCopy, onData, onTimeout);
    if (interestCopy.getInterestLifetimeMilliseconds() >= 0.0)
      // Set up the timeout.
      face.callLater
        (interestCopy.getInterestLifetimeMilliseconds(),
         new Runnable() {
           public void run() { processInterestTimeout(pendingInterest); }
         });

    // Special case: For timeoutPrefix_ we don't actually send the interest.
    if (!timeoutPrefix_.match(interestCopy.getName())) {
      Blob encoding = interestCopy.wireEncode(wireFormat);
      if (encoding.size() > getMaxNdnPacketSize())
        throw new Error
          ("The encoded interest size exceeds the maximum limit getMaxNdnPacketSize()");
      transport_.send(encoding.buf());
    }
  }

  private enum ConnectStatus { UNCONNECTED, CONNECT_REQUESTED, CONNECT_COMPLETE }

  private static class RegisterResponse implements OnData, OnTimeout {
    public RegisterResponse(Info info)
    {
      info_ = info;
    }

    /**
     * We received the response.
     * @param interest
     * @param responseData
     */
    public void
    onData(Interest interest, Data responseData)
    {
      // Decode responseData.getContent() and check for a success code.
      // TODO: Move this into the TLV code.
      TlvDecoder decoder = new TlvDecoder(responseData.getContent().buf());
      long statusCode;
      try {
        decoder.readNestedTlvsStart(Tlv.NfdCommand_ControlResponse);
        statusCode = decoder.readNonNegativeIntegerTlv
             (Tlv.NfdCommand_StatusCode);
      }
      catch (EncodingException ex) {
        logger_.log(Level.INFO,
          "Register prefix failed: Error decoding the NFD response: {0}", ex);
        info_.onRegisterFailed_.onRegisterFailed(info_.prefix_);
        return;
      }

      // Status code 200 is "OK".
      if (statusCode != 200) {
        logger_.log(Level.INFO,
          "Register prefix failed: Expected NFD status code 200, got: {0}", statusCode);
        info_.onRegisterFailed_.onRegisterFailed(info_.prefix_);
        return;
      }

      logger_.log(Level.INFO,
        "Register prefix succeeded with the NFD forwarder for prefix {0}",
        info_.prefix_.toUri());
      if (info_.onRegisterSuccess_ != null)
        info_.onRegisterSuccess_.onRegisterSuccess
          (info_.prefix_, info_.registeredPrefixId_);
    }

    /**
     * We timed out waiting for the response.
     * @param timedOutInterest
     */
    public void
    onTimeout(Interest timedOutInterest)
    {
      logger_.log(Level.INFO,
        "Timeout for NFD register prefix command.");
      info_.onRegisterFailed_.onRegisterFailed(info_.prefix_);
    }

    public static class Info {
      /**
       *
       * @param prefix
       * @param onRegisterFailed
       * @param onRegisterSuccess
       * @param registeredPrefixId The registered prefix ID also returned by
       * registerPrefix.
       */
      public Info
        (Name prefix,OnRegisterFailed onRegisterFailed,
         OnRegisterSuccess onRegisterSuccess, long registeredPrefixId)
      {
        prefix_ = prefix;
        onRegisterFailed_ = onRegisterFailed;
        onRegisterSuccess_ = onRegisterSuccess;
        registeredPrefixId_ = registeredPrefixId;
      }

      public final Name prefix_;
      public final OnRegisterFailed onRegisterFailed_;
      public final OnRegisterSuccess onRegisterSuccess_;
      public final long registeredPrefixId_;
    }

    private final Info info_;
  }

  /**
   * Do the work of registerPrefix to register with NFD.
   * @param registeredPrefixId The getNextEntryId() which registerPrefix got so
   * it could return it to the caller. If this is 0, then don't add to
   * registeredPrefixTable_ (assuming it has already been done).
   * @param prefix
   * @param onInterest
   * @param onRegisterFailed
   * @param onRegisterSuccess
   * @param flags
   * @param commandKeyChain
   * @param commandCertificateName
   * @param wireFormat
   * @param face The face which is passed to the onInterest callback. If
   * onInterest is null, this is ignored.
   * @throws SecurityException If cannot find the private key for the
   * certificateName.
   */
  private void
  nfdRegisterPrefix
    (long registeredPrefixId, Name prefix, OnInterestCallback onInterest,
     OnRegisterFailed onRegisterFailed, OnRegisterSuccess onRegisterSuccess,
     ForwardingFlags flags, KeyChain commandKeyChain,
     Name commandCertificateName, WireFormat wireFormat, Face face)
    throws SecurityException
  {
    if (commandKeyChain == null)
      throw new Error
        ("registerPrefix: The command KeyChain has not been set. You must call setCommandSigningInfo.");
    if (commandCertificateName.size() == 0)
      throw new Error
        ("registerPrefix: The command certificate name has not been set. You must call setCommandSigningInfo.");

    ControlParameters controlParameters = new ControlParameters();
    controlParameters.setName(prefix);
    controlParameters.setForwardingFlags(flags);

    Interest commandInterest = new Interest();

    // Determine whether to use remote prefix registration.
    boolean faceIsLocal;
    try {
      faceIsLocal = isLocal();
    } catch (IOException ex) {
      logger_.log(Level.INFO,
        "Register prefix failed: Error attempting to determine if the face is local: {0}", ex);
      onRegisterFailed.onRegisterFailed(prefix);
      return;
    }

    if (faceIsLocal) {
      commandInterest.setName(new Name("/localhost/nfd/rib/register"));
      // The interest is answered by the local host, so set a short timeout.
      commandInterest.setInterestLifetimeMilliseconds(2000.0);
    }
    else {
      commandInterest.setName(new Name("/localhop/nfd/rib/register"));
      // The host is remote, so set a longer timeout.
      commandInterest.setInterestLifetimeMilliseconds(4000.0);
    }

    // NFD only accepts TlvWireFormat packets.
    commandInterest.getName().append(controlParameters.wireEncode(TlvWireFormat.get()));
    makeCommandInterest
      (commandInterest, commandKeyChain, commandCertificateName,
       TlvWireFormat.get());

    if (registeredPrefixId != 0) {
      long interestFilterId = 0;
      if (onInterest != null) {
        // registerPrefix was called with the "combined" form that includes the
        // callback, so add an InterestFilterEntry.
        interestFilterId = getNextEntryId();
        setInterestFilter
          (interestFilterId, new InterestFilter(prefix), onInterest, face);
      }

      registeredPrefixTable_.add(registeredPrefixId, prefix, interestFilterId);
    }

    // Send the registration interest.
    RegisterResponse response = new RegisterResponse
      (new RegisterResponse.Info
       (prefix, onRegisterFailed, onRegisterSuccess, registeredPrefixId));
    try {
      expressInterest
        (getNextEntryId(), commandInterest, response, response, wireFormat, face);
    }
    catch (IOException ex) {
      // Can't send the interest. Call onRegisterFailed.
      logger_.log(Level.INFO,
        "Register prefix failed: Error sending the register prefix interest to the forwarder: {0}", ex);
      onRegisterFailed.onRegisterFailed(prefix);
    }
  }

  private final Transport transport_;
  private final Transport.ConnectionInfo connectionInfo_;
  private final PendingInterestTable pendingInterestTable_ =
    new PendingInterestTable();
  private final InterestFilterTable interestFilterTable_ =
    new InterestFilterTable();
  private final RegisteredPrefixTable registeredPrefixTable_ =
    new RegisteredPrefixTable(interestFilterTable_);
  private final DelayedCallTable delayedCallTable_ = new DelayedCallTable();
  // Use ArrayList without generics so it works with older Java compilers.
  private final List onConnectedCallbacks_ =
    Collections.synchronizedList(new ArrayList()); // Runnable
  private final CommandInterestGenerator commandInterestGenerator_ =
    new CommandInterestGenerator();
  private final Name timeoutPrefix_ = new Name("/local/timeout");
  private long lastEntryId_;
  private final Object lastEntryIdLock_ = new Object();
  private ConnectStatus connectStatus_ = ConnectStatus.UNCONNECTED;
  private static final Logger logger_ = Logger.getLogger(Node.class.getName());
}
