/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
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

package io.appium.uiautomator2.handler;

import androidx.test.uiautomator.UiObjectNotFoundException;

import io.appium.uiautomator2.common.exceptions.InvalidElementStateException;
import io.appium.uiautomator2.handler.request.SafeRequestHandler;
import io.appium.uiautomator2.http.AppiumResponse;
import io.appium.uiautomator2.http.IHttpRequest;
import io.appium.uiautomator2.model.AndroidElement;
import io.appium.uiautomator2.model.AppiumUIA2Driver;
import io.appium.uiautomator2.model.Session;
import io.appium.uiautomator2.model.api.SendKeysModel;
import io.appium.uiautomator2.utils.Logger;

import static android.text.TextUtils.isEmpty;
import static androidx.test.uiautomator.By.focused;
import static io.appium.uiautomator2.utils.Device.getUiDevice;
import static io.appium.uiautomator2.utils.ElementHelpers.findElement;
import static io.appium.uiautomator2.utils.ModelUtils.toModel;

/**
 * Send keys to a given element.
 */
public class SendKeysToElement extends SafeRequestHandler {

    public SendKeysToElement(String mappedUri) {
        super(mappedUri);
    }

    private static boolean setProgress(AndroidElement element, SendKeysModel model) {
        if (!element.canSetProgress()) {
            return false;
        }

        float value;
        try {
            value = Float.parseFloat(model.text);
        } catch (NumberFormatException | NullPointerException e) {
            throw new IllegalArgumentException(String.format("Cannot convert '%s' to float", model.text));
        }
        Logger.info(String.format("Setting the progress value to %s", value));
        element.setProgress(value);
        return true;
    }

    private static void setText(AndroidElement element, SendKeysModel model) throws UiObjectNotFoundException {
        String text = model.text;
        boolean replace = model.replace != null && model.replace;

        boolean pressEnter = false;
        if (text.endsWith("\\n")) {
            pressEnter = true;
            text = text.replaceFirst("\\n$", "");
            Logger.debug("Will press Enter after setting text");
        }

        if (!replace) {
            String currentText = element.getText();
            if (!isEmpty(currentText)) {
                element.clear();
                if (!isEmpty(element.getText())) {
                    // clear could have failed, or we could have a hint in the field
                    // we'll assume it is the latter
                    Logger.debug("Could not clear the text. Assuming the remainder is a hint text.");
                    currentText = "";
                }
                text = currentText + text;
            }
        }
        if (!element.setText(text)) {
            throw new InvalidElementStateException(String.format("Cannot set the element to '%s'. " +
                    "Did you interact with the correct element?", model.text));
        }

        if (pressEnter) {
            Logger.debug(getUiDevice().pressEnter()
                    ? "Sent Enter key to the device"
                    : "Could not send Enter key to the device");
        }
    }

    @Override
    protected AppiumResponse safeHandle(IHttpRequest request) throws UiObjectNotFoundException {
        String elementId = getElementId(request);
        AndroidElement element;
        if (elementId != null) {
            Session session = AppiumUIA2Driver.getInstance().getSessionOrThrow();
            element = session.getElementsCache().get(elementId);
        } else {
            //perform action on focused element
            element = findElement(focused(true));
        }
        SendKeysModel model = toModel(request, SendKeysModel.class);

        if (setProgress(element, model)) {
            return new AppiumResponse(getSessionId(request));
        }

        setText(element, model);
        return new AppiumResponse(getSessionId(request));
    }
}


