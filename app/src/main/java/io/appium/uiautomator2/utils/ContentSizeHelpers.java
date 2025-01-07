package io.appium.uiautomator2.utils;

import static io.appium.uiautomator2.utils.Device.getUiDevice;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiObject;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.UiObjectNotFoundException;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.InvalidClassException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;

import io.appium.uiautomator2.common.exceptions.UiAutomator2Exception;
import io.appium.uiautomator2.core.AxNodeInfoExtractor;
import io.appium.uiautomator2.core.EventRegister;
import io.appium.uiautomator2.core.ReturningRunnable;
import io.appium.uiautomator2.core.UiObjectChildGenerator;
import io.appium.uiautomator2.model.AccessibilityScrollData;
import io.appium.uiautomator2.model.AndroidElement;
import io.appium.uiautomator2.model.AppiumUIA2Driver;
import io.appium.uiautomator2.model.Session;
import io.appium.uiautomator2.model.UiObject2Element;

public class ContentSizeHelpers {
    // these constants are magic numbers experimentally determined to minimize flakiness in generating
    // last scroll data used in getting the 'contentSize' attribute.
    // TODO see whether anchoring these to time and screen size is more reliable across devices
    private static final int MINI_SWIPE_STEPS = 10;
    private static final int MINI_SWIPE_PIXELS = 200;
    // https://android.googlesource.com/platform/frameworks/testing/+/master/uiautomator/library/core-src/com/android/uiautomator/core/UiScrollable.java#635
    private static final double SWIPE_DEAD_ZONE_PCT = 0.1;

    public static String getContentSize(AndroidElement element) throws UiObjectNotFoundException {
        Rect boundsRect = element.getBounds();
        ContentSize contentSize = new ContentSize(boundsRect);
        try {
            contentSize.touchPadding = getTouchPadding(element);
        } catch (ReflectiveOperationException e) {
            throw new UiAutomator2Exception(e);
        }
        contentSize.scrollableOffset = getScrollableOffset(element);
        return contentSize.toString();
    }

    private static Rect getElementBoundsInScreen(AndroidElement element) {
        return getElementBoundsInScreen(element.getUiObject());
    }

    private static Rect getElementBoundsInScreen(Object uiObject) {
        Logger.debug("Getting bounds in screen for an AndroidElement");
        AccessibilityNodeInfo nodeInfo = AxNodeInfoExtractor.toAxNodeInfo(uiObject);

        Rect rect = new Rect();
        nodeInfo.getBoundsInScreen(rect);
        Logger.debug("Bounds were: " + rect);
        return rect;
    }

    private static int getScrollableOffset(AndroidElement uiScrollable) {
        // get the bounds of the scrollable view
        Rect bounds = getElementBoundsInScreen(uiScrollable);

        // now scroll a bit back and forth in the view to populate the lastScrollData we need
        int x1 = bounds.centerX();
        int y1 = bounds.centerY() + MINI_SWIPE_PIXELS;
        //noinspection UnnecessaryLocalVariable
        int x2 = x1;
        int y2 = y1 - (MINI_SWIPE_PIXELS * 2);
        int yMargin = (int) Math.floor(bounds.height() * SWIPE_DEAD_ZONE_PCT);

        // ensure that our xs and ys are within the bounds of the element
        if (y1 > bounds.height()) {
            y1 = bounds.height() - yMargin;
        }
        if (y2 < 0) {
            y2 = yMargin;
        }

        Session session = AppiumUIA2Driver.getInstance().getSessionOrThrow();
        AccessibilityScrollData lastScrollData;
        Logger.debug("Doing a mini swipe-and-back in the scrollable view to generate scroll data");
        swipe(x1, y1, x2, y2);
        lastScrollData = session.getLastScrollData();
        if (lastScrollData == null) {
            // if we didn't get scroll data from the down swipe, try to get it from the up swipe
            swipe(x2, y2, x1, y1);
            lastScrollData = session.getLastScrollData();
        } else {
            // otherwise just do a reset swipe without worrying about scroll data, to avoid it
            // failing because of flakiness
            getUiDevice().swipe(x2, y2, x1, y1, MINI_SWIPE_STEPS);
        }


        if (lastScrollData == null) {
            throw new UiAutomator2Exception("Could not retrieve accessibility scroll data; unable to determine scrollable offset");
        }

        // if we're in some views, like ScrollViews, we get x/y values directly, and we can simply return
        if (lastScrollData.getMaxScrollY() != -1) {
            return lastScrollData.getMaxScrollY();
        }

        // in other views, like List or Grid, we get item counts and indexes, and we have to turn
        // that into pixels by doing some math
        if (lastScrollData.getItemCount() == -1) {
            throw new UiAutomator2Exception("Did not get either scrollY or itemCount from accessibility scroll data");
        }

        return getScrollableOffsetByItemCount(uiScrollable, lastScrollData.getItemCount());
    }

    private static int getScrollableOffsetByItemCount(AndroidElement uiScrollable, int itemCount) {
        Logger.debug("Figuring out scrollableOffset via item count of " + itemCount);
        Object scrollObject = uiScrollable.getUiObject();
        Rect scrollBounds = getElementBoundsInScreen(uiScrollable);

        // here we loop through the children and get their bounds until the height differs, then
        // regardless of whether we have a list or a grid, we'll know the height of an item/row
        try {
            int itemsPerRow = 0;
            int rowHeight = 0;
            int lastExaminedItemY = Integer.MIN_VALUE; // initialize to something impossibly negative
            int numRowsExamined = 0;
            int numRowsToExamine = 3; // examine a few rows since the top ones often have bad offsets
            Object lastExaminedItem = null;

            UiObjectChildGenerator gen = new UiObjectChildGenerator(scrollObject);
            for (Object item : gen) {
                if (item == null) {
                    throw new UiObjectNotFoundException("Could not get child of scrollview");
                }

                Rect itemBounds = getElementBoundsInScreen(item);

                ++itemsPerRow;
                lastExaminedItem = item;

                if (lastExaminedItemY != Integer.MIN_VALUE && itemBounds.top > lastExaminedItemY) {
                    ++numRowsExamined;
                    rowHeight = itemBounds.top - lastExaminedItemY;
                    if (numRowsExamined >= numRowsToExamine) {
                        break;
                    }
                    // reset itemsPerRow as we examine another row; don't want it to overaccumulate
                    itemsPerRow = 0;
                }

                lastExaminedItemY = itemBounds.top;
            }

            if (lastExaminedItem == null) {
                throw new UiObjectNotFoundException("Could not find any children of the scrollview to get offset from");
            }
            Logger.debug("Determined there were " + itemsPerRow + " items per row");

            if (itemsPerRow == 0) {
                // Need to exit. Other case we will get an ArithmeticException
                return 0;
            }

            int numRows = itemCount / itemsPerRow;
            if (itemCount % itemsPerRow > 0) {
                // we might have an additional part-row
                ++numRows;
            }
            int totalHeight = numRows * rowHeight;
            int scrollableOffset = totalHeight - scrollBounds.height();
            Logger.debug("Determined there were " + numRows + " rows of height " +
                    rowHeight + ", for a total height of " + totalHeight + " and scroll offset " +
                    "of " + scrollableOffset);
            return scrollableOffset;
        } catch (UiObjectNotFoundException ignore) {
        } catch (InvalidClassException e) {
            Logger.error("Programming error, tried to build a UiObjectChildGenerator with wrong type");
        }

        // there were no child items we could find, so assume no offset
        return 0;
    }

    private static int getTouchPadding(
            AndroidElement element
    ) throws UiObjectNotFoundException, ReflectiveOperationException {
        final UiObject2 uiObject2 = element instanceof UiObject2Element
                ? getUiDevice().findObject(By.clazz(((UiObject2) element.getUiObject()).getClassName()))
                : getUiDevice().findObject(By.clazz(((UiObject) element.getUiObject()).getClassName()));
        Field gestureField = uiObject2.getClass().getDeclaredField("mGestures");
        gestureField.setAccessible(true);
        Object gestureObject = Objects.requireNonNull(gestureField.get(uiObject2));

        Field viewConfigField = gestureObject.getClass().getDeclaredField("mViewConfig");
        viewConfigField.setAccessible(true);
        Object viewConfigObject = Objects.requireNonNull(viewConfigField.get(gestureObject));

        Method getScaledPagingTouchSlopMethod = Objects.requireNonNull(
                viewConfigObject.getClass().getDeclaredMethod("getScaledPagingTouchSlop")
        );
        getScaledPagingTouchSlopMethod.setAccessible(true);
        int touchPadding = (int) getScaledPagingTouchSlopMethod.invoke(viewConfigObject);

        return touchPadding / 2;
    }

    @SuppressWarnings("UnusedReturnValue")
    private static boolean swipe(final int startX, final int startY, final int endX, final int endY) {
        Logger.debug(String.format("Swiping from [%s, %s] to [%s, %s]", startX, startY, endX, endY));
        return EventRegister.runAndRegisterScrollEvents(new ReturningRunnable<Boolean>() {
            @Override
            public void run() {
                setResult(getUiDevice().swipe(startX, startY, endX, endY, MINI_SWIPE_STEPS));
            }
        });
    }

    private static class ContentSize {
        private final int width;
        private final int height;
        private final int top;
        private final int left;
        private int scrollableOffset;
        private int touchPadding;

        ContentSize(Rect rect) {
            width = rect.width();
            height = rect.height();
            top = rect.top;
            left = rect.left;
        }

        @Override
        public String toString() {
            JSONObject jsonObject = new JSONObject();
            try {
                jsonObject.put("width", width);
                jsonObject.put("height", height);
                jsonObject.put("top", top);
                jsonObject.put("left", left);
                jsonObject.put("scrollableOffset", scrollableOffset);
                jsonObject.put("touchPadding", touchPadding);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return jsonObject.toString();
        }
    }
}
