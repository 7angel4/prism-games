package explicit;

import prism.ModelType;
import prism.PlayerInfoOwner;
import prism.PrismException;

import java.util.Iterator;
import java.util.Map;

public interface L1CSG<Value> extends UCSG<Value>, L1MDP<Value> {
    @Override
    default ModelType getModelType() {
        return ModelType.L1CSG;
    }

    default int[] getIdles() {
        return getCSGModel().getIdles();
    }

    default Distribution<Value> getNominalChoice(int s, int i) {
        return (Distribution<Value>) getChoice(s, i);
    }


    default Iterator<Map.Entry<Integer, Value>> getTransitionsIterator(int s, int i) {
        return getNominalChoice(s, i).iterator();
    }

    double getRadius(int s, int i);

    void setRadius(int s, int i, double r);

//    void setCentre(int s, int i, Distribution<Value> distr);

    void setCentre(int s, int i, int succ, Value p);
}