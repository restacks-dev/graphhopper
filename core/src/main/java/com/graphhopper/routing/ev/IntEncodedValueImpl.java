/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.routing.ev;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.graphhopper.routing.weighting.DirectAccessWeighting;

import javax.lang.model.SourceVersion;

/**
 * Implementation of the IntEncodedValue via a certain number of bits (that determines the maximum value) and
 * a minimum value (default is 0).
 * With storeTwoDirections = true it can store separate values for forward and reverse edge direction e.g. for one speed
 * value per direction of an edge.
 * With negateReverseDirection = true it supports negating the value for the reverse direction without storing a separate
 * value e.g. to store an elevation slope which is negative for the reverse direction but has otherwise the same value
 * and is used to save storage space.
 */
public class IntEncodedValueImpl implements IntEncodedValue {
    private final String name;
    private final boolean storeTwoDirections;
    final int bits;
    final boolean negateReverseDirection;
    final int minStorableValue;
    final int maxStorableValue;
    final boolean byteSupport;
    int maxValue;

    /**
     * There are multiple int values possible per edge. Here we specify the index into this integer array.
     */
    private int fwdDataIndex;
    private int bwdDataIndex;
    int fwdShift = -1;
    int byteFwdShift = 0;
    int byteFwdOffset = 0;
    int bwdShift = -1;
    int byteBwdShift = 0;
    int byteBwdOffset = 0;
    int fwdMask;
    int bwdMask;

    /**
     * @see #IntEncodedValueImpl(String, int, int, boolean, boolean, boolean)
     */
    public IntEncodedValueImpl(String name, int bits, boolean storeTwoDirections) {
        this(name, bits, 0, false, storeTwoDirections, true);
    }

    /**
     * This creates an EncodedValue to store an integer value with up to the specified bits.
     *
     * @param name                   the key to identify this EncodedValue
     * @param bits                   the bits that should be reserved for storing the value. This determines the
     *                               maximum value.
     * @param minStorableValue       the minimum value. Use e.g. 0 if no negative values are needed.
     * @param negateReverseDirection true if the reverse direction should be always negative of the forward direction.
     *                               This is used to reduce space and store the value only once. If this option is used
     *                               you cannot use storeTwoDirections or a minValue different to 0.
     * @param storeTwoDirections     true if forward and backward direction of the edge should get two independent values.
     * @param byteSupport
     */
    public IntEncodedValueImpl(String name, int bits, int minStorableValue, boolean negateReverseDirection, boolean storeTwoDirections, boolean byteSupport) {
        if (!isValidEncodedValue(name))
            throw new IllegalArgumentException("EncodedValue name wasn't valid: " + name + ". Use lower case letters, underscore and numbers only.");
        if (bits <= 0)
            throw new IllegalArgumentException(name + ": bits cannot be zero or negative");
        if (bits > 31)
            throw new IllegalArgumentException(name + ": at the moment the number of reserved bits cannot be more than 31");
        if (negateReverseDirection && (minStorableValue != 0 || storeTwoDirections))
            throw new IllegalArgumentException(name + ": negating value for reverse direction only works for minValue == 0 " +
                    "and !storeTwoDirections but was minValue=" + minStorableValue + ", storeTwoDirections=" + storeTwoDirections);
        this.name = name;
        this.storeTwoDirections = storeTwoDirections;
        int max = (1 << bits) - 1;
        // negateReverseDirection: store the negative value only once, but for that we need the same range as maxValue for negative values
        this.minStorableValue = negateReverseDirection ? -max : minStorableValue;
        this.maxStorableValue = max + minStorableValue;
        if (minStorableValue == Integer.MIN_VALUE)
            // we do not allow this because we use this value to represent maxValue = untouched, i.e. no value has been set yet
            throw new IllegalArgumentException(Integer.MIN_VALUE + " is not allowed for minValue");
        this.maxValue = Integer.MIN_VALUE;
        // negateReverseDirection: we need twice the integer range, i.e. 1 more bit
        this.bits = negateReverseDirection ? bits + 1 : bits;
        this.negateReverseDirection = negateReverseDirection;
        this.byteSupport = byteSupport;
    }

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    IntEncodedValueImpl(@JsonProperty("name") String name,
                        @JsonProperty("bits") int bits,
                        @JsonProperty("min_storable_value") int minStorableValue,
                        @JsonProperty("max_storable_value") int maxStorableValue,
                        @JsonProperty("max_value") int maxValue,
                        @JsonProperty("negate_reverse_direction") boolean negateReverseDirection,
                        @JsonProperty("store_two_directions") boolean storeTwoDirections,
                        @JsonProperty("fwd_data_index") int fwdDataIndex,
                        @JsonProperty("bwd_data_index") int bwdDataIndex,
                        @JsonProperty("fwd_shift") int fwdShift,
                        @JsonProperty("bwd_shift") int bwdShift,
                        @JsonProperty("fwd_mask") int fwdMask,
                        @JsonProperty("bwd_mask") int bwdMask,
                        @JsonProperty("byte_support") boolean byteSupport
    ) {
        // we need this constructor for Jackson
        this.name = name;
        this.storeTwoDirections = storeTwoDirections;
        this.bits = bits;
        this.negateReverseDirection = negateReverseDirection;
        this.minStorableValue = minStorableValue;
        this.maxStorableValue = maxStorableValue;
        this.maxValue = maxValue;
        this.fwdDataIndex = fwdDataIndex;
        this.bwdDataIndex = bwdDataIndex;
        this.fwdShift = fwdShift;
        this.bwdShift = bwdShift;
        this.fwdMask = fwdMask;
        this.bwdMask = bwdMask;

        this.byteFwdShift = fwdShift % 8;
        this.byteFwdOffset = fwdShift / 8;
        this.byteBwdShift = bwdShift % 8;
        this.byteBwdOffset = bwdShift / 8;
        this.byteSupport = byteSupport;
    }

    @Override
    public final int init(EncodedValue.InitializerConfig init) {
        if (isInitialized())
            throw new IllegalStateException("Cannot call init multiple times");

        init.next(bits);
        this.fwdMask = init.bitMask;
        this.fwdDataIndex = init.dataIndex;
        this.fwdShift = init.shift;
        this.byteFwdShift = fwdShift % 8;
        this.byteFwdOffset = fwdShift / 8;
        if (storeTwoDirections) {
            init.next(bits);
            this.bwdMask = init.bitMask;
            this.bwdDataIndex = init.dataIndex;
            this.bwdShift = init.shift;
            this.byteBwdShift = bwdShift % 8;
            this.byteBwdOffset = bwdShift / 8;
        }

        return storeTwoDirections ? 2 * bits : bits;
    }

    boolean isInitialized() {
        return fwdMask != 0;
    }

    @Override
    public final void setInt(boolean reverse, int edgeId, EdgeBytesAccess edgeAccess, int value) {
        checkValue(value);
        uncheckedSet(reverse, edgeId, edgeAccess, value);
    }

    private void checkValue(int value) {
        if (!isInitialized())
            throw new IllegalStateException("EncodedValue " + getName() + " not initialized");
        if (value > maxStorableValue)
            throw new IllegalArgumentException(name + " value too large for encoding: " + value + ", maxValue:" + maxStorableValue);
        if (value < minStorableValue)
            throw new IllegalArgumentException(name + " value too small for encoding " + value + ", minValue:" + minStorableValue);
    }

    final void uncheckedSet(boolean reverse, int edgeId, EdgeBytesAccess edgeAccess, int value) {
        if (negateReverseDirection) {
            if (reverse) {
                reverse = false;
                value = -value;
            }
        } else if (reverse && !storeTwoDirections)
            throw new IllegalArgumentException(getName() + ": value for reverse direction would overwrite forward direction. Enable storeTwoDirections for this EncodedValue or don't use setReverse");

        maxValue = Math.max(maxValue, value);

        value -= minStorableValue;
        if (reverse) {
            int flags = edgeAccess.getInt(edgeId, bwdDataIndex * 4);
            // clear value bits
            flags &= ~bwdMask;
            edgeAccess.setInt(edgeId, bwdDataIndex * 4, flags | (value << bwdShift));
        } else {
            int flags = edgeAccess.getInt(edgeId, fwdDataIndex * 4);
            flags &= ~fwdMask;
            edgeAccess.setInt(edgeId, fwdDataIndex * 4, flags | (value << fwdShift));
        }
    }

    @Override
    public final int getInt(boolean reverse, int edgeId, EdgeBytesAccess edgeAccess) {
        int value;
        // if we do not store both directions ignore reverse == true for convenient reading
        if (storeTwoDirections && reverse) {
            value = extractValue(edgeId, edgeAccess, bwdDataIndex, byteBwdOffset, byteBwdShift, bwdMask, bwdShift);
            return minStorableValue + value;
        } else {
            value = extractValue(edgeId, edgeAccess, fwdDataIndex, byteFwdOffset, byteFwdShift, fwdMask, fwdShift);
            if (negateReverseDirection && reverse)
                return -(minStorableValue + value);
            return minStorableValue + value;
        }
    }

    private int extractValue(int edgeId, EdgeBytesAccess edgeAccess, int dataIndex, int byteOffset, int shift, int intMask, int intShift) {
        int value;
        int offset = dataIndex * 4;
        if (!byteSupport) return (edgeAccess.getInt(edgeId, offset) & intMask) >>> intShift;

        if (shift + bits <= 8) {
            assert byteOffset >= 0 && byteOffset < 4;
            offset += byteOffset;
            value = DirectAccessWeighting.extract(
                    edgeAccess.getByte(edgeId, offset), bits, shift);
        } else if (shift + bits <= 16) {
            assert byteOffset >= 0 && byteOffset < 3;
            offset += byteOffset;
            value = DirectAccessWeighting.extract(
                    edgeAccess.getByte(edgeId, offset + 1),
                    edgeAccess.getByte(edgeId, offset), bits, shift);
        } else if (shift + bits <= 24) {
            assert byteOffset >= 0 && byteOffset < 2;
            offset += byteOffset;
            value = DirectAccessWeighting.extract(
                    edgeAccess.getByte(edgeId, offset + 2),
                    edgeAccess.getByte(edgeId, offset + 1),
                    edgeAccess.getByte(edgeId, offset), bits, shift);
        } else {
            value = (edgeAccess.getInt(edgeId, offset) & intMask) >>> intShift;
        }

//        if (((origFlags & intMask) >>> intShift) != value) {
//            // int tmp = edgeAccess.getInt(edgeId, offset);
//            throw new IllegalArgumentException(((origFlags & intMask) >>> intShift) + " != " + value
//                    + "\noffset=" + offset + ", byteOffset=" + byteOffset + ", shift=" + shift + ", bits=" + bits +
//                    ", b0=" + edgeAccess.getByte(edgeId, offset) +
//                    ", b1=" + edgeAccess.getByte(edgeId, offset + 1) +
//                    ", b2=" + edgeAccess.getByte(edgeId, offset + 1));
//        }
        return value;
    }

    @Override
    public int getMaxStorableInt() {
        return maxStorableValue;
    }

    @Override
    public int getMinStorableInt() {
        return minStorableValue;
    }

    @Override
    public int getMaxOrMaxStorableInt() {
        return maxValue == Integer.MIN_VALUE ? getMaxStorableInt() : maxValue;
    }

    @Override
    public final boolean isStoreTwoDirections() {
        return storeTwoDirections;
    }

    @Override
    public final String getName() {
        return name;
    }

    @Override
    public final String toString() {
        return getName();
    }

    static boolean isValidEncodedValue(String name) {
        if (name.length() < 2 || name.startsWith("in_") || name.startsWith("backward_")
                || !isLowerLetter(name.charAt(0)) || SourceVersion.isKeyword(name))
            return false;

        int underscoreCount = 0;
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '_') {
                if (underscoreCount > 0) return false;
                underscoreCount++;
            } else if (!isLowerLetter(c) && !Character.isDigit(c)) {
                return false;
            } else {
                underscoreCount = 0;
            }
        }
        return true;
    }

    private static boolean isLowerLetter(char c) {
        return c >= 'a' && c <= 'z';
    }

    public int getBits() {
        return bits;
    }
}
