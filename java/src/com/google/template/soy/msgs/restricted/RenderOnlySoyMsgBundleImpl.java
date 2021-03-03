/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy.msgs.restricted;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.collect.ImmutableList;
import com.google.common.primitives.ImmutableIntArray;
import com.google.common.primitives.ImmutableLongArray;
import com.google.errorprone.annotations.Immutable;
import com.google.template.soy.internal.i18n.BidiGlobalDir;
import com.google.template.soy.msgs.SoyMsgBundle;
import com.ibm.icu.util.ULocale;
import java.util.Comparator;
import java.util.Iterator;
import java.util.NoSuchElementException;
import javax.annotation.Nullable;

/**
 * Represents all renderable messages in a locale.
 *
 * <p>This saves significant memory from the normal SoyMsgBundleImpl, but doesn't store details like
 * message descriptions. This also has small runtime performance penalties, such as using binary
 * search instead of hash tables, constructing wrapper objects on the fly, and computing properties
 * of the message instead of storing them.
 *
 */
@Immutable
final class RenderOnlySoyMsgBundleImpl extends SoyMsgBundle {

  /** The language/locale string of this bundle's messages. */
  private final String localeString;
  private final ULocale locale;
  private final boolean isRtl;

  // Using parallel collections saves memory versus using a Map, because it avoids:
  // * having to wrap the longs in a new Long(), and
  // * avoids wrapping the key/value pair in an Entry.
  // Also, using a sorted collection utilizes memory better, since unlike a hash table, you
  // need neither a linked list nor empty spaces in the hash table.

  /** Sorted array of message ID's that can be binary searched. */
  private final ImmutableLongArray ids;

  /**
   * List containing the message parts. See {@link #partRanges} for an explanation for how they
   * correspond to {@link #ids}.
   */
  private final ImmutableList<SoyMsgPart> values;

  /**
   * Contains index-ranges for parts belonging to messages.
   *
   * <p>For instance, for a message with ID {@code ids[n]}, the SoyMsgPart values belonging to that
   * message are the sublist of {@code values} from {@code partRanges[n]} (inclusive} to {@code
   * partRanges[n+1]} (exclusive).
   */
  private final ImmutableIntArray partRanges;

  /**
   * Constructs a map of render-only soy messages. This implementation saves memory but doesn't
   * store all fields necessary during extraction.
   *
   * @param localeString The language/locale string of this bundle of messages, or null if unknown.
   *     Should only be null for bundles newly extracted from source files. Should always be set for
   *     bundles parsed from message files/resources.
   * @param msgs The list of messages. List order will become the iteration order. Duplicate message
   *     ID's are not permitted.
   */
  public RenderOnlySoyMsgBundleImpl(@Nullable String localeString, Iterable<SoyMsg> msgs) {

    this.localeString = localeString;
    this.locale = localeString == null ? null : new ULocale(localeString);
    this.isRtl = BidiGlobalDir.forStaticLocale(localeString) == BidiGlobalDir.RTL;

    ImmutableList<SoyMsg> sortedMsgs =
        ImmutableList.sortedCopyOf(Comparator.comparingLong(SoyMsg::getId), msgs);

    ImmutableLongArray.Builder idsBuilder = ImmutableLongArray.builder(sortedMsgs.size());
    ImmutableList.Builder<SoyMsgPart> partsBuilder = ImmutableList.builder();
    ImmutableIntArray.Builder partRangesBuilder = ImmutableIntArray.builder(sortedMsgs.size() + 1);
    partRangesBuilder.add(0); // The first range always starts at the beginning of the list.
    long priorId = sortedMsgs.isEmpty() ? -1L : sortedMsgs.get(0).getId() - 1L;
    int runningPartCount = 0;
    for (SoyMsg msg : sortedMsgs) {
      ImmutableList<SoyMsgPart> parts = msg.getParts();

      checkArgument(
          msg.getId() != priorId, "Duplicate messages are not permitted in the render-only impl.");
      checkArgument(
          MsgPartUtils.hasPlrselPart(parts) == msg.isPlrselMsg(),
          "Message's plural/select status is inconsistent -- internal compiler bug.");

      priorId = msg.getId();
      idsBuilder.add(msg.getId());
      partsBuilder.addAll(parts);
      runningPartCount += parts.size();
      partRangesBuilder.add(runningPartCount);
    }

    // This will build the collections in the same order as the sorted map.
    ids = idsBuilder.build();
    values = partsBuilder.build();
    partRanges = partRangesBuilder.build();
  }

  /** Brings a message back to life from only its ID and parts. */
  // The constructor guarantees the type of ImmutableList.
  private SoyMsg resurrectMsg(long id, ImmutableList<SoyMsgPart> parts) {
    return SoyMsg.builder()
        .setId(id)
        .setLocaleString(localeString)
        .setIsPlrselMsg(MsgPartUtils.hasPlrselPart(parts))
        .setParts(parts)
        .build();
  }

  @Override
  public String getLocaleString() {
    return localeString;
  }

  @Override
  @Nullable
  public ULocale getLocale() {
    return locale;
  }

  @Override
  public boolean isRtl() {
    return isRtl;
  }

  private ImmutableList<SoyMsgPart> partsForIndex(int index) {
    int startInclusive = partRanges.get(index);
    int endExclusive = partRanges.get(index + 1);
    return values.subList(startInclusive, endExclusive);
  }

  @Override
  public SoyMsg getMsg(long msgId) {
    int index = binarySearch(msgId);
    return index >= 0 ? resurrectMsg(msgId, partsForIndex(index)) : null;
  }

  @Override
  public ImmutableList<SoyMsgPart> getMsgParts(long msgId) {
    int index = binarySearch(msgId);
    return index >= 0 ? partsForIndex(index) : ImmutableList.of();
  }

  private int binarySearch(long key) {
    int low = 0;
    int high = ids.length() - 1;

    while (low <= high) {
      int mid = (low + high) >>> 1;
      long midVal = ids.get(mid);

      if (midVal < key) {
        low = mid + 1;
      } else if (midVal > key) {
        high = mid - 1;
      } else {
        return mid;
      }
    }
    return -(low + 1);
  }

  @Override
  public int getNumMsgs() {
    return ids.length();
  }

  @Override
  public Iterator<SoyMsg> iterator() {
    return new Iterator<SoyMsg>() {
      int index = 0;

      @Override
      public boolean hasNext() {
        return index < ids.length();
      }

      @Override
      public SoyMsg next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        SoyMsg result = resurrectMsg(ids.get(index), partsForIndex(index));
        index++;
        return result;
      }

      @Override
      public void remove() {
        throw new UnsupportedOperationException("Iterator is immutable");
      }
    };
  }
}
