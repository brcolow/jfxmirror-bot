package org.javafxports.jfxmirror;

import com.amazonaws.services.lambda.runtime.Context; 
import com.amazonaws.services.lambda.runtime.RequestHandler;

public class Bot implements RequestHandler<Integer, String> {
    public String myHandler(int myCount, Context context) {
        return String.valueOf(myCount);
    }
}
